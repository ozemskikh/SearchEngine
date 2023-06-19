package searchengine.data;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import searchengine.model.*;
import searchengine.repositories.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.currentThread;

@RequiredArgsConstructor
public class PagesIndexer extends RecursiveAction {
    private final FieldRepository fieldRepository;
    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    @Setter
    private List<Page> pages;
    @Setter
    private Site site;
    private Float titleWeight;
    private Float bodyWeight;
    private static final int THRESHOLD = 20;
    @NonNull
    private volatile AtomicBoolean isInterrupted;
    private volatile Lemma lemmaObject;
    private final AtomicInteger frequency = new AtomicInteger();
    private final Logger logger = LogManager.getLogger(getClass());

    public synchronized void setIsInterrupted(boolean isInterrupted) {
        this.isInterrupted.set(isInterrupted);
        interruptChecking();
    }

    private void getWeights() {
        titleWeight = fieldRepository.findByName("title").getWeight();
        bodyWeight = fieldRepository.findByName("body").getWeight();
    }

    @Override
    protected void compute() {
        if (interruptChecking()) {
            return;
        }
        getWeights();
        if (this.pages.size() > THRESHOLD) {
            forkJoinIndexingOrganization();
        } else {
            recursivePagesIndexing();
        }
    }

    private void forkJoinIndexingOrganization() {
        List<PagesIndexer> subtasks = new ArrayList<>(createSubtasks());
        for (RecursiveAction subtask : subtasks) {
            if (interruptChecking()) {
                return;
            }
            subtask.fork();
        }
        for (RecursiveAction task : subtasks) {
            if (interruptChecking()) {
                return;
            }
            task.join();
        }
    }

    private void recursivePagesIndexing() {
        for (Page page : pages) {
            if (interruptChecking()) {
                return;
            }
            pageIndexing(page);
        }
    }

    private synchronized boolean interruptChecking() {
        if (isInterrupted.get()) {
            String siteName = site.getName();
            logger.info("Сайт \"" + siteName + "\". Поток индексации " + Thread.currentThread().getName() + " завершён");
            site.setStatus(Status.FAILED);
            site.setLast_error("Индексация сайта прервана пользователем");
            saveSite(site);
            currentThread().interrupt();
            return true;
        }
        return false;
    }

    public void pageIndexing(Page page) {
        if (titleWeight == null || bodyWeight == null) {
            getWeights();
        }
        int siteCode = page.getCode();
        if (siteCode < 400) {
            String url = page.getPath();
            String html = page.getContent();
            Document document = Jsoup.parse(html, url);
            String titleText = document.select("title").text();
            String bodyText = document.select("body").text();
            rankCalculator(titleText, bodyText, url);
        }
    }

    private List<PagesIndexer> createSubtasks() {
        List<PagesIndexer> subtasks = new ArrayList<>();
        PagesIndexer subtask1 = new PagesIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository, isInterrupted);
        subtask1.setPages(pages.subList(0, pages.size() / 2));
        subtask1.setSite(site);
        PagesIndexer subtask2 = new PagesIndexer(fieldRepository, indexRepository, lemmaRepository, pageRepository, siteRepository, isInterrupted);
        subtask2.setPages(pages.subList(pages.size() / 2, pages.size()));
        subtask2.setSite(site);
        subtasks.add(subtask1);
        subtasks.add(subtask2);
        return subtasks;
    }

    private void rankCalculator(String titleText, String bodyText, String url) {
        Map<String, Integer> titleLemmasMap, bodyLemmasMap;
        if ((!titleText.trim().equals("")) && (!bodyText.trim().equals(""))) {
            if (interruptChecking()) {
                return;
            }
            try {
                titleLemmasMap = new Lemmatizer().getAllLemmas(titleText);
                bodyLemmasMap = new Lemmatizer().getAllLemmas(bodyText);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Map<String, Integer> totalLemmasMap = new HashMap<>(titleLemmasMap);
            totalLemmasMap.putAll(bodyLemmasMap);
            countLemmas(totalLemmasMap);
            calculateRanks(titleLemmasMap, bodyLemmasMap, url);
        }
    }

    private void calculateRanks(@NotNull Map<String, Integer> titleMap, Map<String, Integer> bodyMap, String path) {
        String lemma;
        float rank;
        for (Map.Entry<String, Integer> entry : titleMap.entrySet()) {
            if (interruptChecking()) {
                return;
            }
            lemma = entry.getKey();
            rank = entry.getValue() * titleWeight;
            if (bodyMap.containsKey(lemma)) {
                rank += bodyMap.get(lemma) * bodyWeight;
            }
            saveIndex(lemma, path, rank);
        }
        for (Map.Entry<String, Integer> entry : bodyMap.entrySet()) {
            if (interruptChecking()) {
                return;
            }
            lemma = entry.getKey();
            if (!titleMap.containsKey(lemma)) {
                rank = entry.getValue() * bodyWeight;
                saveIndex(lemma, path, rank);
            }
        }
    }

    private void saveIndex(String lemma, String path, Float rank) {
        int lemmaId = lemmaRepository.findByLemmaAndSiteLemma(lemma, site).getId();
        Page page = pageRepository.findByPathAndSitePage(path, site);
        if (Optional.ofNullable(page).isPresent()) {
            indexRepository.save(new Index(page.getId(), lemmaId, rank));
            site.setStatus(Status.INDEXING);
            site.setStatus_time(new Date());
            saveSite(site);
        }
    }

    private void countLemmas(Map<String, Integer> lemmasMap) {
        try {
            String lemma;
            for (Map.Entry<String, Integer> entry : lemmasMap.entrySet()) {
                if (interruptChecking()) {
                    return;
                }
                lemma = entry.getKey().trim();
                saveLemma(lemma);
            }
        } catch (Exception ex) {
            String ExceptionMessage = ex.getMessage();
            logger.error(ExceptionMessage);
            site.setLast_error(ExceptionMessage);
            site.setStatus(Status.FAILED);
            saveSite(site);
        }
    }

    private void saveLemma(String lemma) {
        synchronized (lemmaRepository) {
            if (!lemmaRepository.existsByLemmaAndSiteLemma(lemma, site)) {
                lemmaRepository.save(new Lemma(lemma, 1, site));
            } else {
                lemmaObject = lemmaRepository.findByLemmaAndSiteLemma(lemma, site);
                frequency.set(lemmaObject.getFrequency());
                if (frequency.get() < pageRepository.countBySitePage(site)) {
                    lemmaObject.setFrequency(frequency.incrementAndGet());
                    lemmaRepository.save(lemmaObject);
                }
            }
        }
    }

    public void saveSite(Site site) {
        String url = site.getUrl();
        Site foundSite = siteRepository.findByUrl(url);
        if (Optional.ofNullable(foundSite).isPresent()) {
            site.setId(siteRepository.findByUrl(url).getId());
            site.setLast_error(site.getLast_error());
            site.setStatus(site.getStatus());
            site.setStatus_time(site.getStatus_time());
        }
        siteRepository.save(site);
    }
}