/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.baseline.tasks;

import static java.util.stream.Collectors.toSet;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.slf4j.Logger;

public final class ClassUniquenessAnalyzer {

    private final Map<String, Set<ModuleVersionIdentifier>> classToJarsMap = new HashMap<>();
    private final Map<Set<ModuleVersionIdentifier>, Set<String>> jarsToClasses = new HashMap<>();
    private final Map<String, Set<HashCode>> classToHashCode = new HashMap<>();
    private final Logger log;

    public ClassUniquenessAnalyzer(Logger log) {
        this.log = log;
    }

    public void analyzeConfiguration(Configuration configuration) {
        Instant before = Instant.now();
        Set<ResolvedArtifact> dependencies = configuration
                .getResolvedConfiguration()
                .getResolvedArtifacts();

        Map<String, Set<ModuleVersionIdentifier>> tempClassToJarsMap = new HashMap<>();
        Map<String, Set<HashCode>> tempClassToHashCode = new HashMap<>();

        dependencies.stream().forEach(resolvedArtifact -> {
            File file = resolvedArtifact.getFile();
            if (!file.exists()) {
                log.info("Skipping non-existent jar {}: {}", resolvedArtifact, file);
                return;
            }

            try (FileInputStream fileInputStream = new FileInputStream(file);
                    JarInputStream jarInputStream = new JarInputStream(fileInputStream)) {
                JarEntry entry;
                while ((entry = jarInputStream.getNextJarEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }

                    String className = entry.getName().replaceAll("/", ".").replaceAll(".class", "");
                    HashingInputStream inputStream = new HashingInputStream(Hashing.sha256(), jarInputStream);
                    ByteStreams.exhaust(inputStream);

                    multiMapPut(tempClassToJarsMap,
                            className,
                            resolvedArtifact.getModuleVersion().getId());

                    multiMapPut(tempClassToHashCode,
                            className,
                            inputStream.hash());
                }
            } catch (IOException e) {
                log.error("Failed to read JarFile {}", resolvedArtifact, e);
                throw new RuntimeException(e);
            }
        });

        tempClassToJarsMap.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .forEach(entry -> {
                    // add to the top level map
                    entry.getValue().forEach(value -> multiMapPut(classToJarsMap, entry.getKey(), value));

                    // add to the opposite direction index
                    multiMapPut(jarsToClasses, entry.getValue(), entry.getKey());
                });

        // figure out which classes have differing hashes
        tempClassToHashCode.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .forEach(entry -> {
                    entry.getValue().forEach(value -> multiMapPut(classToHashCode, entry.getKey(), value));
                });

        Instant after = Instant.now();
        log.info("Checked {} classes from {} dependencies for uniqueness ({}ms)",
                tempClassToJarsMap.size(), dependencies.size(), Duration.between(before, after).toMillis());
    }

    public Collection<Set<ModuleVersionIdentifier>> getProblemJars() {
        return classToJarsMap.values();
    }

    public Map<Set<ModuleVersionIdentifier>, Set<String>> jarsToClasses() {
        return jarsToClasses;
    }

    public Set<String> getSharedClassesInProblemJars(Set<ModuleVersionIdentifier> problemJars) {
        return jarsToClasses.get(problemJars);
    }

    public Set<String> getDifferingSharedClassesInProblemJars(Set<ModuleVersionIdentifier> problemJars) {
        return jarsToClasses.get(problemJars).stream()
                .filter(classToHashCode::containsKey)
                .collect(toSet());
    }

    private static <K, V> void multiMapPut(Map<K, Set<V>> map, K key, V value) {
        map.compute(key, (unused, collection) -> {
            Set<V> newCollection = collection != null ? collection : new HashSet<>();
            newCollection.add(value);
            return newCollection;
        });
    }
}