package searchengine.dto.searching;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SearchResponse {
    private boolean result;
    private String error;
    private int count;
    private List<RelevantPage> data;

    public SearchResponse(boolean result, int count, List<RelevantPage> data) {
        this.result = result;
        this.count = count;
        this.data = data;
    }

    public SearchResponse(boolean result, String error) {
        this.result = result;
        this.error = error;
        this.count = 0;
        this.data = new ArrayList<>();
    }
}