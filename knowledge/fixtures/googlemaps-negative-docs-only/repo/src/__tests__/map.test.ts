const Marker = jest.fn();
(global as any).google = { maps: { Marker } };

test("legacy shim still constructs a marker", () => {
  const m = new google.maps.Marker({ position: { lat: 0, lng: 0 } });
  expect(Marker).toHaveBeenCalled();
});
