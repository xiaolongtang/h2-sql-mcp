package com.example.mcp.util;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

public final class JarLocationResolver {

    private JarLocationResolver() {
    }

    public static Path resolveJarDirectory(Class<?> referenceClass) {
        CodeSource codeSource = referenceClass.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            return null;
        }

        URL location = codeSource.getLocation();
        if (location == null) {
            return null;
        }

        try {
            Path resolvedPath = Paths.get(location.toURI());
            if (Files.isRegularFile(resolvedPath)) {
                return resolvedPath.getParent();
            }
            if (Files.isDirectory(resolvedPath)) {
                return resolvedPath;
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return null;
    }
}
