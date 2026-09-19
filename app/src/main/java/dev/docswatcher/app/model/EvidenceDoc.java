package dev.docswatcher.app.model;

public record EvidenceDoc(String path, int line, int column, String snippet, String detector, String layer) {}
