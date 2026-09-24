import FirebaseDynamicLinks
import UIKit

final class LinkHandler {
    func handle(_ url: URL) -> Bool {
        return DynamicLinks.dynamicLinks().handleUniversalLink(url) { link, _ in
            guard let target = link?.url else { return }
            Router.shared.open(target)
        }
    }
}
