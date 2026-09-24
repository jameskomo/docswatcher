const messaging = { sendMulticast: jest.fn(), sendEachForMulticast: jest.fn() };

test("old mock still referenced by a skipped test", async () => {
  await messaging.sendMulticast({ tokens: ["t"] });
  expect(messaging.sendMulticast).toHaveBeenCalled();
});
