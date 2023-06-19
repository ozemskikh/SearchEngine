package searchengine.data;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import searchengine.dto.searching.RelevantPage;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class Searcher {
    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final Site SITE;
    private Map<Integer, Float> absRelevanceMap;
    private final Logger logger = LogManager.getLogger(getClass());

    public ArrayList<RelevantPage> search(String query) {
        String siteName = "";
        try {
            siteName = SITE.getName();
            logger.info("Сайт \"" + siteName + "\". Поиск по запросу: \"" + query + "\"");
            Map<String, Integer> searchedLemmasMap = new Lemmatizer().getAllLemmas(query);
            Map<Integer, Integer> excludeFrequentLemmasMap = excludeFrequentLemmas(searchedLemmasMap);
            if (excludeFrequentLemmasMap.size() == 0) {
                logger.info("Сайт \"" + siteName + "\". Поиск завершён!");
                return new ArrayList<>();
            }
            Map<Integer, Integer> sortedLemmasMap = sortLemmasMap(excludeFrequentLemmasMap);
            Map<Integer, Float> absRelevanceMap = calculateAbsoluteRelevanceMap(sortedLemmasMap);
            Map<Integer, Float> relativeRelevanceMap = calculateRelativeRelevanceMap(absRelevanceMap);
            ArrayList<String> searchedLemmas = new ArrayList<>(searchedLemmasMap.keySet());
            Map<Integer, Float> sortedRelevanceMap = sortRelevanceMap(relativeRelevanceMap);
            ArrayList<RelevantPage> relevantPages = getRelevantPages(sortedRelevanceMap, searchedLemmas);
            logger.info("Сайт \"" + siteName + "\". Поиск завершён!");
            return relevantPages;
        } catch (Exception ex) {
            logger.error("Сайт \"" + siteName + "\". Ошибка: " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<Integer, Integer> excludeFrequentLemmas(Map<String, Integer> lemmasMap) {
        Map<Integer, Integer> excludeFrequentLemmasMap = new HashMap<>();
        float existPercentage;
        int pageCountWithLemma, totalPageCount;
        for (Map.Entry<String, Integer> lemmaEntry : lemmasMap.entrySet()) {
            String lemma = lemmaEntry.getKey();
            boolean isLemmaExist = lemmaRepository.existsByLemmaAndSiteLemma(lemma, SITE);
            if (!isLemmaExist) {
                continue;
            }
            Lemma lemmaObject = lemmaRepository.findByLemmaAndSiteLemma(lemma, SITE);
            Integer frequency = lemmaObject.getFrequency();
            pageCountWithLemma = Optional.of(frequency).orElse(0);
            totalPageCount = pageRepository.countBySitePage(SITE);
            existPercentage = ((float) pageCountWithLemma / (float) totalPageCount) * 100;
            final int FIND_LIMIT_PERCENTAGE = 80;
            if (existPercentage < FIND_LIMIT_PERCENTAGE) {
                excludeFrequentLemmasMap.put(lemmaObject.getId(), pageCountWithLemma);
            }
        }
        return excludeFrequentLemmasMap;
    }

    private Map<Integer, Integer> sortLemmasMap(Map<Integer, Integer> lemmasMap) {
        return lemmasMap.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getValue))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> {
                            throw new AssertionError();
                        },
                        LinkedHashMap::new
                ));
    }

    private Map<Integer, Float> calculateAbsoluteRelevanceMap(Map<Integer, Integer> sortedLemmasMap) {
        absRelevanceMap = new HashMap<>();
        for (Map.Entry<Integer, Integer> sortedLemmaEntry : sortedLemmasMap.entrySet()) {
            int lemmaId = sortedLemmaEntry.getKey();
            List<Integer> pageIdList = indexRepository.findAllByLemmaId(lemmaId).stream().map(Index::getPageId).toList();
            for (int pageId : pageIdList) {
                getAbsoluteRelevance(lemmaId, pageId);
            }
        }
        return absRelevanceMap;
    }

    private void getAbsoluteRelevance(int lemmaId, int pageId) {
        float absRelevance = indexRepository.findByLemmaIdAndPageId(lemmaId, pageId).getRank();
        if (absRelevanceMap.containsKey(pageId)) {
            absRelevance += absRelevanceMap.get(pageId);
        }
        absRelevanceMap.put(pageId, absRelevance);
    }

    private Map<Integer, Float> calculateRelativeRelevanceMap(Map<Integer, Float> absRelevanceMap) {
        float maxAbsRel = Collections.max(absRelevanceMap.values());
        return absRelevanceMap
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        b -> b.getValue() / maxAbsRel,
                        (a, b) -> {
                            throw new AssertionError();
                        },
                        HashMap::new
                ));
    }

    private Map<Integer, Float> sortRelevanceMap(Map<Integer, Float> lemmasMap) {
        return lemmasMap.entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> {
                            throw new AssertionError();
                        },
                        LinkedHashMap::new
                ));
    }

    private ArrayList<RelevantPage> getRelevantPages(Map<Integer, Float> relevanceMap, ArrayList<String> searchedLemmas) {
        ArrayList<RelevantPage> relevantPages = new ArrayList<>();
        for (Map.Entry<Integer, Float> relevanceEntry : relevanceMap.entrySet()) {
            int pageId = relevanceEntry.getKey();
            Page page = pageRepository.findById(pageId).orElse(new Page());
            String html = page.getContent();
            String snippet = new Lemmatizer().findMatches(html, searchedLemmas).toString();
            snippet = snippet.substring(1, snippet.length() - 1);
            if (snippet.trim().equals("")) {
                continue;
            }
            int siteId = page.getSitePage().getId();
            Site site = siteRepository.findById(siteId).orElse(new Site());
            String url = site.getUrl();
            String siteName = site.getName();
            String uri = page.getPath();
            Document document = Jsoup.parse(html);
            String title = document.select("title").text();
            float relevance = relevanceEntry.getValue();
            relevantPages.add(new RelevantPage(url, siteName + "|pageId=" + pageId, uri, title, snippet, relevance));
        }
        return relevantPages;
    }

}