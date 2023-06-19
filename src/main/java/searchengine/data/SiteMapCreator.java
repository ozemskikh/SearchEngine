package searchengine.data;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.config.UserData;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;

@RequiredArgsConstructor
public class SiteMapCreator extends RecursiveTask<HashSet<Page>> {
    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    @Setter
    private Page page;
    @Setter
    private Site site;
    @Setter
    private UserData userData;
    private Document document;
    HashSet<Page> pagesHashSet = new HashSet<>();
    private volatile AtomicBoolean isInterrupted = new AtomicBoolean(false);

    public void setIsInterrupted(boolean isInterrupted) {
        this.isInterrupted = new AtomicBoolean(isInterrupted);
    }

    private final Logger logger = LogManager.getLogger(getClass());

    @Override
    protected HashSet<Page> compute() {
        if (interruptChecking()) {
            return null;
        }
        collectPagesData();
        List<SiteMapCreator> taskList = new ArrayList<>();
        for (Page child : page.getChildren()) {
            if (interruptChecking()) {
                return null;
            }
            SiteMapCreator task = new SiteMapCreator(indexRepository, lemmaRepository, pageRepository, siteRepository);
            task.setPage(child);
            task.setSite(site);
            task.setUserData(userData);
            taskList.add(task);
            task.fork();
        }
        for (SiteMapCreator task : taskList) {
            if (interruptChecking()) {
                return null;
            }
            pagesHashSet.addAll(task.join());
        }
        return pagesHashSet;
    }

    private void collectPagesData() {
        try {
            sleep(600);
            getPageData(page);
            Elements elements = document.select("body").select("a");
            for (Element a : elements) {
                if (interruptChecking()) {
                    return;
                }
                String childUrl = a.absUrl("href");
                if (isCorrectUrl(childUrl)) {
                    childUrl = stripParams(childUrl);
                    page.addChild(new Page(childUrl, site));
                    pagesHashSet.add(page);
                }
            }
        } catch (Exception ex) {
            catchException(ex);
        }
    }

    private void catchException(Exception ex) {
        if (ex.getClass().equals(HttpStatusException.class)) {
            logger.error("Ошибка. Статус: " + ((HttpStatusException) ex).getStatusCode()
                    + ". Страница: " + ((HttpStatusException) ex).getUrl());
            page.setCode(((HttpStatusException) ex).getStatusCode());
            pagesHashSet.add(page);
        } else if (ex.getClass().equals(SocketTimeoutException.class)) {
            logger.error("Время ожидания вышло! Страница: " + page.getPath());
        } else if (ex.getClass().equals(InterruptedException.class)) {
            logger.error("Произошло прерывание потока для обхода страниц сайта");
        } else if (ex.getClass().equals(UnknownHostException.class)) {
            logger.error("Ошибка имени хоста или отсутствие подключения к Интернету");
        } else {
            logger.error("Ошибка: " + ex.getMessage());
        }
    }

    public Page getPageData(Page page) throws IOException {
        Connection.Response response = Jsoup.connect(page.getPath())
                .timeout(3000)
                .userAgent(userData.getUserAgent())
                .referrer(userData.getReferrer())
                .execute();
        document = response.parse();
        page.setCode(response.statusCode());
        page.setContent(document.html());
        return page;
    }

    private boolean interruptChecking() {
        if (isInterrupted.get()) {
            currentThread().interrupt();
            return true;
        }
        return false;
    }

    private String stripParams(String url) {
        return url.replaceAll("\\?.+", "");
    }

    private boolean isCorrectUrl(String url) {
        Pattern patternRoot = Pattern.compile("^" + page.getPath());
        Pattern patternNotFile = Pattern.compile("(\\S+(\\.(?i)(jpg|png|gif|bmp|pdf))$)");
        Pattern patternNotAnchor = Pattern.compile("#([\\w\\-]+)?$");
        Pattern patternNotParam = Pattern.compile(".*\\?.+");
        return patternRoot.matcher(url).lookingAt()
                && !patternNotFile.matcher(url).find()
                && !patternNotAnchor.matcher(url).find()
                && !patternNotParam.matcher(url).find();
    }

}