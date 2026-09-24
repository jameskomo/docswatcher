# Store locator

We migrated off the deprecated classes in 2025. The old code looked like this:

```js
const marker = new google.maps.Marker({ position, map });
const service = new google.maps.DirectionsService();
```

The legacy endpoint https://maps.googleapis.com/maps/api/directions/json is no longer called.
