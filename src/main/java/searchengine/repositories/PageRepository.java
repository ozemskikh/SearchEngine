package searchengine.repositories;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;
import searchengine.model.Site;

import java.util.List;

@Repository
public interface PageRepository extends CrudRepository<Page, Integer> {
    Integer countBySitePage(Site site);

    Page findByPathAndSitePage(String path, Site site);

    List<Page> findBySitePage(Site site);
}