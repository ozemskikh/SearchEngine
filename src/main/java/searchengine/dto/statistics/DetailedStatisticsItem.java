package searchengine.dto.statistics;

import lombok.Data;
import searchengine.model.Status;

@Data
public class DetailedStatisticsItem {
    private String url;
    private String name;
    private Status status;
    private long statusTime;
    private String error;
    private int pages;
    private int lemmas;
}