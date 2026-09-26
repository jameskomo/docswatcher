package dev.docswatcher.app.forge;

import dev.docswatcher.app.store.Repo;
import java.util.List;
import org.springframework.stereotype.Component;

/** Picks the {@link Forge} a repository lives on, by its recorded provider. */
@Component
public class Forges {

  private final List<Forge> forges;

  public Forges(List<Forge> forges) {
    this.forges = forges;
  }

  public Forge of(Repo repo) {
    for (Forge f : forges) {
      if (f.host().equals(repo.provider())) {
        return f;
      }
    }
    throw new IllegalStateException("No forge for provider " + repo.provider() + " of " + repo.fullName());
  }
}
