package searchengine.services.implementation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.Status;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.StatisticsService;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    private final SitesList sites;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private TotalStatistics total = new TotalStatistics();
    private List<DetailedStatisticsItem> detailed = new ArrayList<>();

    @Override
    public StatisticsResponse getStatistics() {
        total = new TotalStatistics();
        total.setSites(sites.getSites().size());
        total.setIndexing(isIndexing());
        detailed = new ArrayList<>();
        List<Site> sitesList = sites.getSites();
        if (siteRepository.count() > 0) {
            getSitesStatisticsFromDB(sitesList);
        }
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }

    private void getSitesStatisticsFromDB(List<Site> sitesList) {
        for (Site siteItem : sitesList) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            String url = siteItem.getUrl();
            item.setUrl(url);
            item.setName(siteItem.getName());
            if (!siteRepository.existsByUrl(url)) {
                continue;
            }
            searchengine.model.Site site = siteRepository.findByUrl(url);
            item.setStatus(site.getStatus());
            item.setStatusTime(site.getStatus_time().getTime());
            item.setError(site.getLast_error());
            int pagesCount = pageRepository.countBySitePage(site);
            int lemmasCount = lemmaRepository.countBySiteLemma(site);
            item.setPages(pagesCount);
            item.setLemmas(lemmasCount);
            total.setPages(total.getPages() + pagesCount);
            total.setLemmas(total.getLemmas() + lemmasCount);
            detailed.add(item);
        }
    }

    private boolean isIndexing() {
        List<searchengine.model.Site> siteList = (List<searchengine.model.Site>) siteRepository.findAll();
        for (searchengine.model.Site site : siteList) {
            if (site.getStatus().equals(Status.INDEXING)) {
                return true;
            }
        }
        return false;
    }
}
