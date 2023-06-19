package searchengine.data;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;

import java.io.IOException;
import java.util.*;

public class Lemmatizer {
    private final Map<String, Integer> lemmasMap = new HashMap<>();
    static LuceneMorphology russianLuceneMorphology;

    static {
        try {
            russianLuceneMorphology = new RussianLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static LuceneMorphology englishLuceneMorphology;

    static {
        try {
            englishLuceneMorphology = new EnglishLuceneMorphology();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ArrayList<String> getAllElements(List<Node> nodes) {
        ArrayList<String> textElemList = new ArrayList<>();
        for (Node node : nodes) {
            textElemList.addAll(getNodeElements(node));
        }
        return textElemList;
    }

    private ArrayList<String> getNodeElements(Node node) {
        ArrayList<String> textElemList = new ArrayList<>();
        if (node.childNodeSize() == 0) {
            if (node.toString().trim().equals("")) {
                return new ArrayList<>();
            }
            textElemList.add(node.toString().trim());
        } else {
            List<Node> list = node.childNodes();
            textElemList.addAll(getAllElements(list));
        }
        return textElemList;
    }

    public ArrayList<String> findMatches(String html, ArrayList<String> searchedLemmas) {
        HashSet<String> findSet;
        ArrayList<String> resultList = new ArrayList<>();
        Document document = Jsoup.parse(html);
        List<Node> childNodes = document.childNodes();
        ArrayList<String> list = getAllElements(childNodes);
        for (String elementText : list) {
            findSet = getNormalFormWords(elementText, searchedLemmas);
            if (findSet.size() == 0) {
                continue;
            }
            for (String findWord : findSet) {
                resultList.add(elementText.replaceAll(findWord, "<b>" + findWord + "</b>"));
            }
        }
        return resultList;
    }

    private HashSet<String> getNormalFormWords(String elementText, ArrayList<String> searchedLemmas) {
        HashSet<String> findSet = new HashSet<>();
        String[] allWords = getWordsFromText(elementText, true);
        for (String word : allWords) {
            word = word.trim();
            if (word.equals("") || word.trim().length() == 1) {
                continue;
            }
            String wordBaseForm = getNormalForm(word);
            if (wordBaseForm.trim().equals("")) {
                continue;
            }
            if (!isServicePart(word) && searchedLemmas.contains(wordBaseForm.toLowerCase())) {
                findSet.add(word);
            }
        }
        return findSet;
    }

    private String[] getWordsFromText(String text, boolean originalCase) {
        if (originalCase) {
            return text
                    .replaceAll("[\n|\t]|\\d-?", " ")
                    .replaceAll("[^\\.а-яa-z\\s|а-яa-z-а-яa-z|А-ЯA-Z-А-ЯA-Z]", " ")
                    .replaceAll("\\.[^a-z]{2,3}", " ")
                    .replaceAll("\\W\\b[а-яa-z]\\b\\W", " ")
                    .replaceAll(" +", " ")
                    .replaceAll("^ ", "")
                    .split("[,\\s]+");
        }
        return text.toLowerCase()
                .replaceAll("[\n|\t]|\\d-?", " ")
                .replaceAll("[^\\.а-яa-z\\s|а-яa-z-а-яa-z]", " ")
                .replaceAll("\\.[^a-z]{2,3}", " ")
                .replaceAll("\\W\\b[а-яa-z]\\b\\W", " ")
                .replaceAll(" +", " ")
                .replaceAll("^ ", "")
                .split("[,\\s]+");
    }

    public Map<String, Integer> getAllLemmas(String text) throws IOException {
        String[] allWords = getWordsFromText(text, false);
        for (String word : allWords) {
            getLemmaByWord(word);
        }
        return lemmasMap;
    }

    private void getLemmaByWord(String word) {
        if (!isServicePart(word.trim()) && word.trim().length() != 1) {
            String wordBaseForm = getNormalForm(word);
            if (wordBaseForm.trim().equals("") || wordBaseForm.trim().length() == 1) {
                return;
            }
            wordBaseForm = wordBaseForm.replaceAll("^-|-$", "");
            addLemmaToMap(wordBaseForm);
        }
    }

    private void addLemmaToMap(String lemma) {
        if (lemmasMap.containsKey(lemma)) {
            int count = lemmasMap.get(lemma);
            lemmasMap.put(lemma, ++count);
        } else {
            lemmasMap.put(lemma, 1);
        }
    }

    private static boolean isServicePart(String word) {
        boolean bool = false;
        List<String> morphs = getMorphology(word);
        for (String morph : morphs) {
            if (morph.contains("МЕЖД") || morph.contains("СОЮЗ") || morph.contains("ПРЕДЛ") || morph.contains("ЧАСТ") || morph.contains("МС")) {
                bool = true;
                break;
            }
        }
        return bool;
    }

    private static List<String> getMorphology(String word) {
        if (russianLuceneMorphology.checkString(word)) {
            return russianLuceneMorphology.getMorphInfo(word);
        }
        if (englishLuceneMorphology.checkString(word)) {
            return englishLuceneMorphology.getMorphInfo(word);
        }
        return new ArrayList<>();
    }

    private static String getNormalForm(String word) {
        if (russianLuceneMorphology.checkString(word)) {
            return russianLuceneMorphology.getNormalForms(word).get(0);
        }
        if (englishLuceneMorphology.checkString(word)) {
            return englishLuceneMorphology.getNormalForms(word).get(0);
        }
        return "";
    }
}