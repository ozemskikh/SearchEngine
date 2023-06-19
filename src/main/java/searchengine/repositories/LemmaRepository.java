package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;
import searchengine.model.Site;

import java.util.List;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    Lemma findByLemmaAndSiteLemma(String lemma, Site site);

    boolean existsByLemmaAndSiteLemma(String lemma, Site site);

    Integer countBySiteLemma(Site site);

    List<Lemma> findAllBySiteLemma(Site site);
}