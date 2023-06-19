package searchengine.data;

import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import searchengine.config.UserData;
import searchengine.model.Field;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.*;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

public class OneSiteIndexer extends Thread {
    private final FieldRepository fieldRepository;
    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    @Setter
    private String ROOT_URL;
    @Setter
    private String ROOT_URL_NAME;
    @Setter
    private UserData userData;
    private PagesIndexer recursiveIndexing;
    private SiteMapCreator siteMapCreator;
    private ForkJoinPool forkJoinPool;
    private volatile AtomicBoolean isInterrupted;
    private final Logger logger = LogManager.getLogger(getClass());

    public OneSiteIndexer(FieldRepository fieldRepository, IndexRepository indexRepository, LemmaRepository lemmaRepository,
                          PageRepository pageRepository, SiteRepository siteRepository, AtomicBoolean isInterrupted) {
        this.fieldRepository = fieldRepository;
        this.indexRepository = indexRepository;
        this.lemmaRepository = lemmaRepository;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
        this.isInterrupted = isInterrupted;
    }

    public synchronized void setIsInterrupted(boolean isInterrupted) {
        this.isInterrupted.set(isInterrupted);
        interruptChecking();
    }

    @Override
    public void run() {
        try {
            fullIndexing();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void interruptChecking() {
        if (isInterrupted.get()) {
            currentThread().interrupt();
            String infoText = "Индексация остановлена пользователем";
            logger.info(infoText);
        }
    }

    public void fullIndexing() {
        try {
            if (isInterrupted.get()) {
                siteMapCreator.setIsInterrupted(true);
                return;
            }
            logger.info(getPrefixForLogger() + "обход страниц сайта начат!");
            getAndSavePages();
            logger.info(getPrefixForLogger() + "информация добавлена в базу данных!");
            if (isInterrupted.get()) {
                return;
            }
            indexTablesInitialization();
            if (isInterrupted.get()) {
                recursiveIndexing.setIsInterrupted(true);
                return;
            }
            indexing();
        } catch (Exception exception) {
            logger.error(getPrefixForLogger() + "Ошибка: " + exception.getMessage());
            Site site = siteRepository.findByUrl(ROOT_URL);
            saveSiteChanges(site, exception.getCause().toString());
        }
    }

    private String getPrefixForLogger() {
        return "Сайт \"" + ROOT_URL_NAME + "\": ";
    }

    private void getAndSavePages() {
        Site site = siteRepository.findByUrl(ROOT_URL);
        Page root = new Page(ROOT_URL, site);
        siteMapCreator = new SiteMapCreator(indexRepository, lemmaRepository, pageRepository, siteRepository);
        siteMapCreator.setPage(root);
        siteMapCreator.setSite(site);
        siteMapCreator.setUserData(userData);
        forkJoinPool = new ForkJoinPool();
        forkJoinPool.submit(siteMapCreator);
        if (interruptionWaiting(site, false)) {
            return;
        }
        HashSet<Page> pagesHashSet;
        try {
            pagesHashSet = siteMapCreator.get();
        } catch (Exception ex) {
            executionMessage(ex, site);
            return;
        }
        pagesHashSet = modifyPathsInPages(pagesHashSet);
        ArrayList<Page> pagesList = new ArrayList<>(pagesHashSet);
        Collections.sort(pagesList);
        logger.info(getPrefixForLogger() + "обход страниц сайта закончен!");
        logger.info(getPrefixForLogger() + "идёт добавление информации в базу данных!");
        pageRepository.saveAll(pagesList);
    }

    private void executionMessage(Exception ex, Site site) {
        String errorText = (ex.getClass().equals(InterruptedException.class))
                ? "Обход страниц прерван пользователем"
                : "Ошибка при обходе страниц сайта";
        saveSiteChanges(site, errorText);
        logger.error(getPrefixForLogger() + errorText + ": " + ex.getMessage());
    }

    private boolean interruptionWaiting(Site site, boolean indexing) {
        if (indexing) {
            return interruptWaitingByIndexing(site);
        } else {
            return interruptWaitingBySiteMapCreation(site);
        }
    }

    private boolean interruptWaitingByIndexing(Site site) {
        while (!recursiveIndexing.isDone()) {
            if (Thread.currentThread().isInterrupted()) {
                recursiveIndexing.setIsInterrupted(true);
                forkJoinPool.shutdownNow();
                String warnText = "Индексация остановлена пользователем";
                saveSiteChanges(site, warnText);
                logger.info(getPrefixForLogger() + warnText);
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    private boolean interruptWaitingBySiteMapCreation(Site site) {
        while (!siteMapCreator.isDone()) {
            if (Thread.currentThread().isInterrupted()) {
                String warnText = "Обход страниц сайта прерван пользователем";
                saveSiteChanges(site, warnText);
                logger.info(getPrefixForLogger() + warnText);
                siteMapCreator.setIsInterrupted(true);
                forkJoinPool.shutdownNow();
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    private void indexTablesInitialization() {
        logger.info(getPrefixForLogger() + "идёт проверка/инициализация таблиц индексации!");
        if (!(fieldRepository.existsByName("title") && fieldRepository.existsByName("title"))) {
            Field titleField = new Field("title", "title", 1.0F);
            Field bodyField = new Field("body", "body", 0.8F);
            fieldRepository.saveAll(List.of(titleField, bodyField));
        }
        logger.info(getPrefixForLogger() + "проверка/инициализация таблиц индексации завершена!");
    }

    private void indexing() {
        Site site = siteRepository.findByUrl(ROOT_URL);
        logger.info(getPrefixForLogger() + "идёт индексация страниц!");
        List<Page> pages = pageRepository.findBySitePage(site);
        recursiveIndexing = new PagesIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository,
                new AtomicBoolean(false));
        recursiveIndexing.setPages(pages);
        recursiveIndexing.setSite(site);
        forkJoinPool.shutdownNow();
        forkJoinPool = new ForkJoinPool();
        forkJoinPool.submit(recursiveIndexing);
        if (interruptionWaiting(site, true)) {
            return;
        }
        logger.info(getPrefixForLogger() + "индексация страниц завершена!");
        saveSiteChanges(site, Status.INDEXED);
    }

    private synchronized void saveSiteChanges(Site site, String lastError) {
        site.setLast_error(lastError);
        site.setStatus(Status.FAILED);
        site.setStatus_time(new Date());
        new PagesIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository,
                new AtomicBoolean(false)).saveSite(site);
    }

    public synchronized void saveSiteChanges(Site site, Status status) {
        site.setStatus(status);
        site.setStatus_time(new Date());
        site.setLast_error("");
        new PagesIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository,
                new AtomicBoolean(false)).saveSite(site);
    }

    public HashSet<Page> modifyPathsInPages(HashSet<Page> pagesHashSet) {
        HashSet<Page> resultPagesHashSet = new HashSet<>();
        ArrayList<String> pathList = new ArrayList<>();
        for (Page page : pagesHashSet) {
            String path = page.getPath().trim()
                    .replaceFirst(ROOT_URL, "")
                    .trim();
            if (!path.equals("") && !pathList.contains(path)) {
                pathList.add(path);
                page.setPath(path);
            } else {
                continue;
            }
            resultPagesHashSet.add(page);
        }
        return resultPagesHashSet;
    }
}