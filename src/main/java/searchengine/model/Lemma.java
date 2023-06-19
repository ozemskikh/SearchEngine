package searchengine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import javax.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@Entity
@Table(name = "lemma")
public class Lemma {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @NonNull
    @Column(nullable = false)
    private String lemma;
    @NonNull
    @Column(nullable = false)
    private Integer frequency;

    @NonNull
    @ManyToOne(cascade = CascadeType.MERGE, fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", referencedColumnName = "id", nullable = false)
    private Site siteLemma;

    @Transient
    @ManyToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinTable(name = "'index'",
            joinColumns = @JoinColumn(name = "lemma_id", referencedColumnName = "id"),
            inverseJoinColumns = @JoinColumn(name = "page_id", referencedColumnName = "id"))
    private Set<Page> pages = new HashSet<>();
}