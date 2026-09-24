package com.acme.referrals;

import android.net.Uri;
import com.google.android.gms.tasks.Task;
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks;
import com.google.firebase.dynamiclinks.ShortDynamicLink;

public final class InviteLinks {
    public Task<ShortDynamicLink> create(String userId) {
        return FirebaseDynamicLinks.getInstance()
            .createDynamicLink()
            .setLink(Uri.parse("https://acme.example/invite/" + userId))
            .setDomainUriPrefix("https://acme.page.link")
            .buildShortDynamicLink();
    }
}
