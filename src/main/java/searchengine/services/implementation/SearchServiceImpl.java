package searchengine.services.implementation;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import searchengine.data.Searcher;
import searchengine.dto.searching.RelevantPage;
import searchengine.dto.searching.SearchResponse;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.SearchService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private String lastQuery = "";
    private String lastSite;
    private ArrayList<RelevantPage> lastResults;
    private ArrayList<RelevantPage> results;
    private final Logger logger = LogManager.getLogger(getClass());

    public SearchResponse search(String query, String site, int offset, int limit) {
        if (query.trim().equals(lastQuery) && !lastQuery.trim().equals("") && site.trim().equals(lastSite)) {
            results = lastResults;
        } else {
            SearchResponse searchResponse = newQuerySearch(query, site);
            if (searchResponse != null) {
                return searchResponse;
            }
        }
        int count = results.size();
        if (count == 0) {
            return createFalseSearchResponse("По поисковому запросу ничего не нашлось");
        }
        lastQuery = query.trim();
        lastSite = site;
        lastResults = results;
        results = resultLimiting(results, count, offset, limit);
        return new SearchResponse(true, count, results);
    }

    private SearchResponse newQuerySearch(String query, String site) {
        if (query.trim().equals("")) {
            return createFalseSearchResponse("Задан пустой поисковый запрос");
        }
        if (indexRepository.count() == 0) {
            return createFalseSearchResponse("Никакой из сайтов еще не проиндексирован! Запустите индексацию!");
        }
        if (site.equals("All sites")) {
            try {
                results = searchByAllSites(query);
            } catch (InterruptedException e) {
                return createFalseSearchResponse("Ошибка поиска по сайтам");
            }
        } else {
            Status status = siteRepository.findByUrl(site).getStatus();
            if (status != Status.INDEXED) {
                return createFalseSearchResponse("Сайт еще не проиндексирован! Запустите индексацию!");
            }
            results = searchBySite(query, site);
        }
        return null;
    }

    private SearchResponse createFalseSearchResponse(String message) {
        logger.info(message);
        return new SearchResponse(false, message);
    }

    private Callable<ArrayList<RelevantPage>> callableSearchBySite(String query, String site) {
        return () -> searchBySite(query, site);
    }

    private ArrayList<RelevantPage> searchByAllSites(String query) throws InterruptedException {
        ArrayList<String> sites = siteRepository.findAllUrlByStatus(Status.INDEXED);
        ExecutorService executor = Executors.newWorkStealingPool();
        List<Callable<ArrayList<RelevantPage>>> callableSearches = new ArrayList<>();
        for (String url : sites) {
            callableSearches.add(callableSearchBySite(query, url));
        }
        return executor.invokeAll(callableSearches)
                .stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .collect(ArrayList::new, List::addAll, List::addAll);
    }

    private ArrayList<RelevantPage> searchBySite(String query, String url) {
        Site site = siteRepository.findByUrl(url);
        Searcher searcher = new Searcher(indexRepository, lemmaRepository, pageRepository, siteRepository, site);
        return searcher.search(query);
    }

    private ArrayList<RelevantPage> resultLimiting(ArrayList<RelevantPage> results, int count, int offset, int limit) {
        int startIndex = 0;
        int endIndex = count;
        if (offset > 0 && offset < endIndex) {
            startIndex = offset;
        }
        if (limit <= count - offset) {
            endIndex = startIndex + limit;
        }
        return new ArrayList<>(results.subList(startIndex, endIndex));
    }
}
