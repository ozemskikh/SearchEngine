package searchengine.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import javax.persistence.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Getter
@Setter
@NoArgsConstructor(force = true)
@Entity
@Table(name = "page")
public class Page implements Comparable<Page> {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NonNull
    @Column(columnDefinition = "text not null, unique key (path(180), site_id)")
    private volatile String path;

    @Column(nullable = false)
    private int code;

    @NonNull
    @Column(nullable = false, columnDefinition = "mediumtext")
    private String content;

    @ManyToOne(cascade = CascadeType.REFRESH, fetch = FetchType.EAGER)
    @JoinColumn(name = "site_id", referencedColumnName = "id", nullable = false)
    private Site sitePage;

    @Transient
    @ManyToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinTable(name = "'index'",
            joinColumns = @JoinColumn(name = "page_id", referencedColumnName = "id"),
            inverseJoinColumns = @JoinColumn(name = "lemma_id", referencedColumnName = "id"))
    private Set<Lemma> lemmas = new HashSet<>();

    @Transient
    private volatile Page parent;

    @Transient
    private volatile CopyOnWriteArrayList<Page> children;

    public Page(@NotNull String path, Site sitePage) {
        this.path = path;
        parent = null;
        children = new CopyOnWriteArrayList<>();
        this.code = 200;
        this.content = "";
        this.sitePage = sitePage;
    }

    public synchronized void addChild(@NotNull Page node) {
        Page root = getRootElement();
        if (!root.isContainsUrl(node.getPath())) {
            node.setParent(this);
            children.add(node);
        }
    }

    private boolean isContainsUrl(String url) {
        if (this.path.equals(url)) {
            return true;
        }
        for (Page child : children) {
            if (child.isContainsUrl(url))
                return true;
        }
        return false;
    }

    @Override
    public int compareTo(@NotNull Page o) {
        return this.path.trim().compareTo(o.path.trim());
    }

    public Page getRootElement() {
        return parent == null ? this : parent.getRootElement();
    }

    private void setParent(Page node) {
        synchronized (this) {
            this.parent = node;
        }
    }

    public String getContent() {
        return content.replace("'", "\\'");
    }
}