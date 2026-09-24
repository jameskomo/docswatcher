import 'package:firebase_dynamic_links/firebase_dynamic_links.dart';

Future<Uri?> initialLink() async {
  final data = await FirebaseDynamicLinks.instance.getInitialLink();
  return data?.link;
}
