package io.sesam.dukemicroservice;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import no.priv.garshol.duke.InMemoryLinkDatabase;
import no.priv.garshol.duke.Link;

public class SinceAwareInMemoryLinkDatabase extends InMemoryLinkDatabase {

    public void assertLink(Link link) {
        Collection<Link> linkset = getAllLinksFor(link.getID1());
        if (linkset != null) {
            for (Link oldlink : linkset) {
                if (oldlink.equals(link)) {
                    // This links is between the same two ids. If the link is otherwise identical we don't have to
                    // do anything else. We need to do this check in order to avoid replacing the existing link with
                    // an identical link with a different timestamp.
                    if (link.getStatus() == oldlink.getStatus() && link.getKind() == oldlink.getKind()) {
                        double diff = Math.abs(link.getConfidence() - oldlink.getConfidence());
                        if (diff < 0.000001) {
                            return;
                        }
                    }
                    break;
                }
            }
        }
        super.assertLink(link);
    }

    public List<Link> getChangesSince(long since) {
        List<Link> links = new LinkedList<>();
        for (Link link: getAllLinks()) {
            if (link.getTimestamp() > since) {
                links.add(link);
            }
        }
        return links;
    }

}