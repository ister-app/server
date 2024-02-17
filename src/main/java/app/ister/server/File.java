package app.ister.server;

public record File(
        String name,
        String parent,
        String folder,
        long size) { }
