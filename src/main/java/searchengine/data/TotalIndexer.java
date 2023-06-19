package searchengine.data;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import searchengine.config.Site;
import searchengine.config.UserData;
import searchengine.model.*;
import searchengine.repositories.*;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public class TotalIndexer extends Thread {
    private final FieldRepository fieldRepository;
    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final List<Site> yamlSites;
    private final UserData userData;
    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private volatile boolean isStopped = false;
    private final Logger logger = LogManager.getLogger(getClass());

    public synchronized void setIsStopped(boolean isStopped) {
        this.isStopped = isStopped;
        if (this.isStopped) {
            try {
                executor.shutdownNow();
                currentThread().interrupt();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    @Override
    public void run() {
        if (!runTotalIndexing()) {
            String message = "Индексация остановлена пользователем";
            logger.info(message);
            siteRepository.findAll().forEach(site -> {
                site.setLast_error(message);
                site.setStatus(Status.FAILED);
                new PagesIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository,
                        new AtomicBoolean(false)).saveSite(site);
            });
        }
    }

    private boolean runTotalIndexing() {
        isStopped = false;
        if (!updatingSites()) {
            return false;
        }
        for (Site site : yamlSites) {
            if (!runSiteIndexing(site)) {
                return false;
            }
        }
        return true;
    }

    private boolean runSiteIndexing(Site siteItem) {
        String url = siteItem.getUrl();
        String siteName = siteItem.getName();
        searchengine.model.Site site = siteRepository.findByUrl(url);
        try {
            boolean isDeletedOk = executor.submit(() -> deleteIndexingSiteInfo(site)).get();
            if (!isDeletedOk) {
                return false;
            }
        } catch (InterruptedException | ExecutionException e) {
            return false;
        }
        if (isStopped) {
            return false;
        }
        OneSiteIndexer oneSiteIndexer = new OneSiteIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository,
                new AtomicBoolean(false));
        oneSiteIndexer.setROOT_URL(url);
        oneSiteIndexer.setROOT_URL_NAME(siteName);
        oneSiteIndexer.setUserData(userData);
        if (isStopped) {
            oneSiteIndexer.setIsInterrupted(true);
            return false;
        }
        int coresCount = Runtime.getRuntime().availableProcessors();
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(coresCount);
        executor.execute(oneSiteIndexer);
        return true;
    }

    private boolean updatingSites() {
        logger.info("Обновление информации о сайтах начато!");
        if (!updateSites()) {
            return false;
        }
        logger.info("Информация о сайтах обновлена!");
        return true;
    }

    private boolean deleteIndexingSiteInfo(searchengine.model.Site site) {
        if ((isStopped) || !(deleteSiteInfo(site))) {
            return false;
        }
        logger.info("Удаление информации из БД об индексируемом сайте \"" + site.getName() + "\" закончено!");
        return true;
    }

    private boolean deleteSiteInfo(searchengine.model.Site site) {
        List<Lemma> lemmaListToDelete = lemmaRepository.findAllBySiteLemma(site);
        lemmaRepository.deleteAll(lemmaListToDelete);
        String infoPrefix = "Сайт \"" + site.getName() + "\": ";
        logger.info(infoPrefix + "удаление лемм завершено!");
        if (isStopped) {
            return false;
        }
        List<Page> pageListToDelete = pageRepository.findBySitePage(site);
        pageRepository.deleteAll(pageListToDelete);
        logger.info(infoPrefix + "удаление страниц завершено!");
        if (isStopped) {
            return false;
        }
        List<Integer> pageIdListToDelete = pageListToDelete.stream().map(Page::getId).toList();
        List<Index> indexListToDelete = indexRepository.findAllByPageIdIn(pageIdListToDelete);
        indexRepository.deleteAll(indexListToDelete);
        logger.info(infoPrefix + "удаление индексов завершено!");
        return true;
    }

    private boolean updateSites() {
        if (!addingNewSites()) {
            return false;
        }
        if (isStopped) {
            return false;
        }
        return deletingSites();
    }

    private boolean addingNewSites() {
        Iterable<searchengine.model.Site> sitesInDB = siteRepository.findAll();
        for (Site yamlSite : yamlSites) {
            processYamlSite(sitesInDB, yamlSite);
            if (isStopped) {
                return false;
            }
        }
        return true;
    }

    private void processYamlSite(Iterable<searchengine.model.Site> dbSites, Site yamlSite) {
        boolean isPresent = false;
        for (searchengine.model.Site dbSite : dbSites) {
            if (yamlSite.getUrl().equals(dbSite.getUrl())) {
                isPresent = true;
                break;
            }
        }
        searchengine.model.Site site = new searchengine.model.Site(Status.INDEXING, new Date(), yamlSite.getUrl(), yamlSite.getName());
        new PagesIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository,
                new AtomicBoolean(false)).saveSite(site);
        if (!isPresent) {
            logger.info("Добавлен новый сайт \"" + yamlSite.getUrl() + "\" в БД!");
        }
    }

    private boolean deletingSites() {
        Iterable<searchengine.model.Site> dbSites = siteRepository.findAll();
        for (searchengine.model.Site dbSite : dbSites) {
            processDBSite(dbSite);
            if (isStopped) {
                return false;
            }
        }
        return true;
    }

    private void processDBSite(searchengine.model.Site dbSite) {
        boolean isPresent = false;
        for (Site site : yamlSites) {
            if (dbSite.getUrl().equals(site.getUrl())) {
                isPresent = true;
                break;
            }
        }
        if (!isPresent) {
            siteRepository.delete(dbSite);
            logger.info("Удалён неиндексируемый сайт \"" + dbSite.getUrl() + "\" из БД!");
        }
    }
}