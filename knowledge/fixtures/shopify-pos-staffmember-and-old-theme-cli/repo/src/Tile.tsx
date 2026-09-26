import { reactExtension, useApi } from '@shopify/ui-extensions/point-of-sale';

function LoyaltyTile() {
  const { session } = useApi();
  const staffId = session.currentSession.staffMemberId;
  return <Tile title="Loyalty" subtitle={`Staff ${staffId}`} enabled />;
}

export default reactExtension('pos.home.tile.render', () => <LoyaltyTile />);
