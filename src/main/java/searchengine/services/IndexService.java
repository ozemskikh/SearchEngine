package searchengine.services;

import searchengine.dto.indexing.IndexingResponse;

public interface IndexService {
    IndexingResponse startIndexing();
    IndexingResponse stopIndexing();
    IndexingResponse indexPage(String url);
}
