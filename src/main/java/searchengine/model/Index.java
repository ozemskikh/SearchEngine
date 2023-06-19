package searchengine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import javax.persistence.*;

@Data
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@Entity
@Table(name = "`index`")
public class Index {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @NonNull
    @Column(name = "page_id", nullable = false)
    private Integer pageId;
    @NonNull
    @Column(name = "lemma_id", nullable = false)
    private Integer lemmaId;
    @NonNull
    @Column(name = "`rank`", nullable = false, scale = 1)
    private Float rank;
}