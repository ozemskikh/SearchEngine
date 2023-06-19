package searchengine.services.implementation;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.config.UserData;
import searchengine.data.PagesIndexer;
import searchengine.data.SiteMapCreator;
import searchengine.data.TotalIndexer;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.model.Page;
import searchengine.model.Status;
import searchengine.repositories.*;
import searchengine.services.IndexService;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class IndexServiceImpl implements IndexService {
    private final FieldRepository fieldRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final SitesList sites;
    private final UserData userData;
    private ThreadPoolExecutor executor;
    private TotalIndexer indexing;
    private URL fullURL;
    private String rootUrl;
    private String rootUrlWithWWW;
    private Page processedPage;
    private final Logger logger = LogManager.getLogger(getClass());

    public IndexingResponse startIndexing() {
        List<Site> sitesList = sites.getSites();
        for (Site site : sitesList) {
            boolean isSiteExists = siteRepository.existsByUrl(site.getUrl());
            if (!isSiteExists) {
                continue;
            }
            Status status = siteRepository.findByUrl(site.getUrl()).getStatus();
            if (status == Status.INDEXING) {
                return new IndexingResponse(false, "Индексация уже запущена");
            }
        }
        indexing = new TotalIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository, sitesList, userData);
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        executor.execute(indexing);
        return new IndexingResponse(true);
    }

    public IndexingResponse indexPage(String url) {
        logger.info("Страница \"" + url + "\": индексация запущена!");
        List<Site> sitesList = sites.getSites();
        List<String> siteUrls = sitesList.stream()
                .map(Site::getUrl)
                .map(value -> {
                    try {
                        return new URL(value);
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(URL::getAuthority)
                .map(value -> value.startsWith("www.") ? value : "www." + value)
                .toList();
        return addOrUpdatePage(url, siteUrls);
    }

    public IndexingResponse stopIndexing() {
        if (executor.getTaskCount() == 0) {
            return new IndexingResponse(false, "Индексация не запущена");
        }
        List<Site> sitesList = sites.getSites();
        boolean isIndexing = false;
        for (Site site : sitesList) {
            String url = site.getUrl();
            Status status = siteRepository.findByUrl(url).getStatus();
            if (status == Status.INDEXING) {
                isIndexing = true;
                break;
            }
        }
        if (isIndexing) {
            indexing.setIsStopped(true);
            try {
                executor.shutdownNow();
            } catch (Exception ex) {
                logger.error(ex.getMessage());
            }
            return new IndexingResponse(true);
        }
        return new IndexingResponse(false, "Индексация не запущена");
    }

    private IndexingResponse getRootUrlWithWWW(String url) {
        try {
            fullURL = new URL(url);
        } catch (MalformedURLException e) {
            return new IndexingResponse(false, "Ошибка определения формата URL-адреса страницы при индексации");
        }
        rootUrl = fullURL.getAuthority();
        rootUrlWithWWW = rootUrl.startsWith("www.") ? rootUrl : "www." + rootUrl;
        return null;
    }

    private IndexingResponse addOrUpdatePage(String url, @NotNull List<String> siteUrls) {
        IndexingResponse indexingResponse = getRootUrlWithWWW(url);
        if (indexingResponse != null) {
            return indexingResponse;
        }
        if (!siteUrls.contains(rootUrlWithWWW)) {
            return new IndexingResponse(false, "Данная страница находится за пределами сайтов, " +
                    "указанных в конфигурационном файле");
        }
        indexingResponse = processPage(url);
        if (indexingResponse != null) {
            return indexingResponse;
        }
        logger.info("Страница \"" + url + "\": индексация завершена!");
        return new IndexingResponse(true);
    }

    private IndexingResponse processPage(String url) {
        rootUrl = rootUrl.startsWith("http") ? rootUrl : fullURL.getProtocol() + "://" + rootUrl;
        searchengine.model.Site site = siteRepository.findByUrl(rootUrl);
        String path = url.replaceFirst(rootUrl, "/");
        path = path.startsWith("//") ? path.substring(1) : path;
        processedPage = pageRepository.findByPathAndSitePage(path, site);
        if (Optional.ofNullable(processedPage).isPresent()) {
            int processedPageId = processedPage.getId();
            indexRepository.deleteAllByPageId(processedPageId);
            processedPage.setPath(rootUrl);
        } else {
            processedPage = new Page(url, site);
        }
        IndexingResponse indexingResponse = getPageData(site, url);
        if (indexingResponse != null) {
            return indexingResponse;
        }
        processedPage.setPath(path);
        pageRepository.save(processedPage);
        List<Page> pages = (List<Page>) pageRepository.findAll();
        PagesIndexer indexer = new PagesIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository, new AtomicBoolean(false));
        indexer.setPages(pages);
        indexer.setSite(site);
        indexer.pageIndexing(processedPage);
        saveIndexedSiteStatus(site);
        return null;
    }

    private IndexingResponse getPageData(searchengine.model.Site site, String url) {
        try {
            SiteMapCreator siteMapCreator = new SiteMapCreator(indexRepository, lemmaRepository, pageRepository, siteRepository);
            siteMapCreator.setPage(processedPage);
            siteMapCreator.setSite(site);
            siteMapCreator.setUserData(userData);
            processedPage = siteMapCreator.getPageData(processedPage);
        } catch (IOException e) {
            String errorText = "Ошибка получения данных индексируемой страницы";
            logger.error("Страница \"" + url + "\":" + errorText);
            return new IndexingResponse(false, errorText);
        }
        return null;
    }

    private void saveIndexedSiteStatus(searchengine.model.Site site) {
        site.setStatus(Status.INDEXED);
        siteRepository.save(site);
    }
}
