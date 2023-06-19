package searchengine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;

import javax.persistence.*;
import java.util.Collection;
import java.util.Date;

@Data
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@Entity
@Table(name = "site")
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @NonNull
    @Column(columnDefinition = "ENUM('INDEXING', 'INDEXED', 'FAILED') not null")
    @Enumerated(EnumType.STRING)
    private Status status;
    @NonNull
    @Column(nullable = false)
    private Date status_time;
    @Nullable
    @Column(columnDefinition = "text")
    private String last_error;
    @NonNull
    @Column(nullable = false)
    private String url;
    @NonNull
    @Column(nullable = false)
    private String name;
    @OneToMany(mappedBy = "siteLemma", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    private Collection<Lemma> lemmas;
    @OneToMany(mappedBy = "sitePage", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    private Collection<Page> pages;
}