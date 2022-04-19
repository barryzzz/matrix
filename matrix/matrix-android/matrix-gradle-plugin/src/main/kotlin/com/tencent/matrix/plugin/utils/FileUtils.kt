/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.matrix.plugin.utils

import com.android.utils.PathUtils
import com.google.common.base.Charsets
import com.google.common.base.Joiner
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import com.google.common.collect.FluentIterable
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
import com.google.common.hash.Hashing
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.util.regex.Pattern
import java.util.stream.Collectors

// These are utility methods, meant to be public.
object FileUtils {
    private val PATH_JOINER = Joiner.on(File.separatorChar)
    private val COMMA_SEPARATED_JOINER = Joiner.on(", ")
    private val UNIX_NEW_LINE_JOINER = Joiner.on('\n')

    /**
     * Recursively deletes a path.
     *
     * @param path the path delete, may exist or not
     * @throws IOException failed to delete the file / directory
     */
    @Throws(IOException::class)
    fun deletePath(path: File) {
        deleteRecursivelyIfExists(path)
    }

    /**
     * Recursively deletes a directory content (including the sub directories) but not itself.
     *
     * @param directory the directory, that must exist and be a valid directory
     * @throws IOException failed to delete the file / directory
     */
    @Throws(IOException::class)
    fun deleteDirectoryContents(directory: File) {
        Preconditions.checkArgument(directory.isDirectory, "!directory.isDirectory")
        val files = directory.listFiles()
        Preconditions.checkNotNull(files)
        for (file in files) {
            deletePath(file)
        }
    }

    /**
     * Makes sure `path` is an empty directory. If `path` is a directory, its contents
     * are removed recursively, leaving an empty directory. If `path` is not a directory,
     * it is removed and a directory created with the given path. If `path` does not
     * exist, a directory is created with the given path.
     *
     * @param path the path, that may exist or not and may be a file or directory
     * @throws IOException failed to delete directory contents, failed to delete `path` or
     * failed to create a directory at `path`
     */
    @Throws(IOException::class)
    fun cleanOutputDir(path: File) {
        if (!path.isDirectory) {
            if (path.exists()) {
                deletePath(path)
            }
            if (!path.mkdirs()) {
                throw IOException(String.format("Could not create empty folder %s", path))
            }
            return
        }
        deleteDirectoryContents(path)
    }

    /**
     * Copies a regular file from one path to another, preserving file attributes. If the
     * destination file exists, it gets overwritten.
     */
    @Throws(IOException::class)
    fun copyFile(from: File, to: File) {
        Files.copy(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.COPY_ATTRIBUTES,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    /**
     * Copies a directory from one path to another. If the destination directory exists, the file
     * contents are merged and files from the source directory overwrite files in the destination.
     */
    @Throws(IOException::class)
    fun copyDirectory(from: File, to: File) {
        Preconditions.checkArgument(from.isDirectory, "Source path is not a directory.")
        Preconditions.checkArgument(
            !to.exists() || to.isDirectory,
            "Destination path exists and is not a directory."
        )
        mkdirs(to)
        val children = from.listFiles()
        if (children != null) {
            for (child in children) {
                if (child.isFile) {
                    copyFileToDirectory(child, to)
                } else if (child.isDirectory) {
                    copyDirectoryToDirectory(child, to)
                } else {
                    throw IllegalArgumentException(
                        "Don't know how to copy file " + child.absolutePath
                    )
                }
            }
        }
    }

    /**
     * Makes a copy of the given file in the specified directory, preserving the name and file
     * attributes.
     */
    @Throws(IOException::class)
    fun copyFileToDirectory(from: File, to: File) {
        copyFile(from, File(to, from.name))
    }

    /**
     * Makes a copy of the given directory in the specified destination directory.
     *
     * @see .copyDirectory
     */
    @Throws(IOException::class)
    fun copyDirectoryToDirectory(from: File, to: File) {
        copyDirectory(from, File(to, from.name))
    }

    /**
     * Makes a copy of the directory's content, in the specified location, while maintaining the
     * directory structure. So the entire directory tree from the source will be copied.
     *
     * @param from directory from which the content is copied
     * @param to   destination directory, will be created if does not exist
     */
    @Throws(IOException::class)
    fun copyDirectoryContentToDirectory(
        from: File, to: File
    ) {
        Preconditions.checkArgument(from.isDirectory, "Source path is not a directory.")
        val children = from.listFiles()
        if (children != null) {
            for (f in children) {
                if (f.isDirectory) {
                    val destination = File(to, relativePath(f, from))
                    com.google.common.io.Files.createParentDirs(destination)
                    mkdirs(destination)
                    copyDirectoryContentToDirectory(f, destination)
                } else if (f.isFile) {
                    val destination = File(to, relativePath(f.parentFile, from))
                    com.google.common.io.Files.createParentDirs(destination)
                    mkdirs(destination)
                    copyFileToDirectory(f, destination)
                }
            }
        }
    }

    /**
     * Creates a directory, if it doesn't exist.
     *
     * @param folder the directory to create, may already exist
     * @return `folder`
     */
    fun mkdirs(folder: File): File {
        // attempt to create first.
        // if failure only throw if folder does not exist.
        // This makes this method able to create the same folder(s) from different thread
        if (!folder.mkdirs() && !folder.isDirectory) {
            throw RuntimeException("Cannot create directory $folder")
        }
        return folder
    }

    /**
     * Deletes an existing file or an existing empty directory.
     *
     * @param file the file or directory to delete. The file/directory must exist, if the directory
     * exists, it must be empty.
     */
    @Throws(IOException::class)
    fun delete(file: File) {
        Files.delete(file.toPath())
    }

    /**
     * Deletes a file or an empty directory if it exists.
     *
     * @param file the file or directory to delete. The file/directory may not exist; if the
     * directory exists, it must be empty.
     */
    @Throws(IOException::class)
    fun deleteIfExists(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    /**
     * Deletes a file or a directory if it exists. If the directory is not empty, its contents will
     * be deleted recursively.
     *
     * @param file the file or directory to delete. The file/directory may not exist; if the
     * directory exists, it may be non-empty.
     */
    @Throws(IOException::class)
    fun deleteRecursivelyIfExists(file: File) {
        PathUtils.deleteRecursivelyIfExists(file.toPath())
    }

    @Throws(IOException::class)
    fun renameTo(file: File, to: File) {
        val result = file.renameTo(to)
        if (!result) {
            throw IOException("Failed to rename " + file.absolutePath + " to " + to)
        }
    }

    /**
     * Joins a list of path segments to a given File object.
     *
     * @param dir   the file object.
     * @param paths the segments.
     * @return a new File object.
     */
    fun join(dir: File, vararg paths: String): File {
        return if (paths.size == 0) {
            dir
        } else File(
            dir,
            PATH_JOINER.join(paths)
        )
    }

    /**
     * Joins a list of path segments to a given File object.
     *
     * @param dir   the file object.
     * @param paths the segments.
     * @return a new File object.
     */
    fun join(dir: File, paths: Iterable<String>): File {
        return File(dir, PATH_JOINER.join(removeEmpty(paths)))
    }

    /**
     * Joins a set of segment into a string, separating each segments with a host-specific
     * path separator.
     *
     * @param paths the segments.
     * @return a string with the segments.
     */
    fun join(vararg paths: String): String {
        return PATH_JOINER.join(removeEmpty(Lists.newArrayList(*paths)))
    }

    /**
     * Joins a set of segment into a string, separating each segments with a host-specific
     * path separator.
     *
     * @param paths the segments.
     * @return a string with the segments.
     */
    fun join(paths: Iterable<String?>): String {
        return PATH_JOINER.join(paths)
    }

    private fun removeEmpty(input: Iterable<String>): Iterable<String> {
        return Lists.newArrayList(input)
            .stream()
            .filter { it: String -> !it.isEmpty() }
            .collect(Collectors.toList())
    }

    /**
     * Loads a text file forcing the line separator to be of Unix style '\n' rather than being
     * Windows style '\r\n'.
     */
    @Throws(IOException::class)
    fun loadFileWithUnixLineSeparators(file: File): String {
        return UNIX_NEW_LINE_JOINER.join(com.google.common.io.Files.readLines(file, Charsets.UTF_8))
    }

    /**
     * Computes the relative of a file or directory with respect to a directory.
     *
     * @param file the file or directory, which must exist in the filesystem
     * @param dir  the directory to compute the path relative to
     * @return the relative path from `dir` to `file`; if `file` is a directory
     * the path comes appended with the file separator (see documentation on `relativize`
     * on java's `URI` class)
     */
    fun relativePath(file: File, dir: File): String {
        Preconditions.checkArgument(
            file.isFile || file.isDirectory, "%s is not a file nor a directory.",
            file.path
        )
        Preconditions.checkArgument(dir.isDirectory, "%s is not a directory.", dir.path)
        return relativePossiblyNonExistingPath(file, dir)
    }

    /**
     * Computes the relative of a file or directory with respect to a directory.
     * For example, if the file's absolute path is `/a/b/c` and the directory
     * is `/a`, this method returns `b/c`.
     *
     * @param file the path that may not correspond to any existing path in the filesystem
     * @param dir  the directory to compute the path relative to
     * @return the relative path from `dir` to `file`; if `file` is a directory
     * the path comes appended with the file separator (see documentation on `relativize`
     * on java's `URI` class)
     */
    fun relativePossiblyNonExistingPath(file: File, dir: File): String {
        val path = dir.toURI().relativize(file.toURI()).path
        return toSystemDependentPath(path)
    }

    /**
     * Converts a /-based path into a path using the system dependent separator.
     *
     * @param path the system independent path to convert
     * @return the system dependent path
     */
    fun toSystemDependentPath(path: String): String {
        var path = path
        if (File.separatorChar != '/') {
            path = path.replace('/', File.separatorChar)
        }
        return path
    }

    /**
     * Escapes all OS dependent chars if necessary.
     *
     * @param path the file path to escape.
     * @return the escaped file path or itself if it is not necessary.
     */
    fun escapeSystemDependentCharsIfNecessary(path: String): String {
        return if (File.separatorChar == '\\') {
            path.replace("\\", "\\\\")
        } else path
    }

    /**
     * Converts a system-dependent path into a /-based path.
     *
     * @param path the system dependent path
     * @return the system independent path
     */
    fun toSystemIndependentPath(path: String): String {
        var path = path
        if (File.separatorChar != '/') {
            path = path.replace(File.separatorChar, '/')
        }
        return path
    }

    /**
     * Returns an absolute path that can be open by system APIs for all platforms.
     *
     * @param file The file whose path needs to be converted.
     * @return On non-Windows platforms, the absolute path of the file. On Windows, the absolute
     * path preceded by "\\?\". This ensures that Windows API calls can open the path even if it
     * is more than 260 characters long.
     */
    fun toExportableSystemDependentPath(file: File): String {
        return if (File.separatorChar != '/' && !file.absolutePath.startsWith("\\\\?\\")) {
            "\\\\?\\" + file.absolutePath
        } else file.absolutePath
    }

    @Throws(IOException::class)
    fun sha1(file: File): String {
        return Hashing.sha1().hashBytes(com.google.common.io.Files.toByteArray(file)).toString()
    }

    fun getAllFiles(dir: File): FluentIterable<File> {
        return FluentIterable.from(
            com.google.common.io.Files.fileTraverser().depthFirstPreOrder(dir)
        )
            .filter(com.google.common.io.Files.isFile())
    }

    fun getNamesAsCommaSeparatedList(files: Iterable<File>): String {
        return COMMA_SEPARATED_JOINER.join(Iterables.transform<File, String>(files) { obj: File? -> obj!!.name })
    }

    /**
     * Replace all unsafe characters for a file name (OS independent) with an underscore
     *
     * @param input an potentially unsafe file name
     * @return a safe file name
     */
    fun sanitizeFileName(input: String): String {
        return input.replace("[:\\\\/*\"?|<>']".toRegex(), "_")
    }

    /**
     * Chooses a directory name, based on a JAR file name, considering exploded-aar and classes.jar.
     */
    fun getDirectoryNameForJar(inputFile: File): String {
        // add a hash of the original file path.
        val hashFunction = Hashing.sha1()
        val hashCode = hashFunction.hashString(inputFile.absolutePath, Charsets.UTF_16LE)
        var name = com.google.common.io.Files.getNameWithoutExtension(inputFile.name)
        if (name == "classes" && inputFile.absolutePath.contains("exploded-aar")) {
            // This naming scheme is coming from DependencyManager#computeArtifactPath.
            val versionDir = inputFile.parentFile.parentFile
            val artifactDir = versionDir.parentFile
            val groupDir = artifactDir.parentFile
            name = Joiner.on('-').join(
                groupDir.name, artifactDir.name, versionDir.name
            )
        }
        name = name + "_" + hashCode.toString()
        return name
    }

    /**
     * Creates a new text file with the given content. The file should not exist when this method
     * is called.
     *
     * @param file    the file to write to
     * @param content the new content of the file
     */
    @Throws(IOException::class)
    fun createFile(file: File, content: String) {
        Preconditions.checkArgument(!file.exists(), "%s exists already.", file)
        writeToFile(file, content)
    }

    /**
     * Creates a new text file or replaces content of an existing file.
     *
     * @param file    the file to write to
     * @param content the new content of the file
     */
    @Throws(IOException::class)
    fun writeToFile(file: File, content: String) {
        com.google.common.io.Files.createParentDirs(file)
        com.google.common.io.Files.asCharSink(file, StandardCharsets.UTF_8).write(content)
    }

    /**
     * Find a list of files in a directory, using a specified path pattern.
     */
    fun find(base: File, pattern: Pattern): List<File> {
        Preconditions.checkArgument(
            base.isDirectory,
            "'%s' must be a directory.",
            base.absolutePath
        )
        return FluentIterable.from(
            com.google.common.io.Files.fileTraverser().depthFirstPreOrder(base)
        )
            .filter { file: File? ->
                pattern.matcher(
                    toSystemIndependentPath(
                        file!!.path
                    )
                )
                    .find()
            }
            .toList()
    }

    /**
     * Find a file with the specified name in a given directory .
     */
    fun find(base: File, name: String): Optional<File> {
        Preconditions.checkArgument(
            base.isDirectory,
            "'%s' must be a directory.",
            base.absolutePath
        )
        return FluentIterable.from(
            com.google.common.io.Files.fileTraverser().depthFirstPreOrder(base)
        )
            .filter { file: File? -> name == file!!.name }
            .last()
    }

    /**
     * Join multiple file paths as String.
     */
    fun joinFilePaths(files: Iterable<File>): String {
        return Joiner.on(File.pathSeparatorChar)
            .join(Iterables.transform<File, String>(files) { obj: File? -> obj!!.absolutePath })
    }

    /**
     * Returns `true` if the parent directory of the given file/directory exists, and `false` otherwise. Note that this method resolves the real path of the given file/directory
     * first via [File.getCanonicalFile].
     */
    fun parentDirExists(file: File): Boolean {
        val canonicalFile: File
        canonicalFile = try {
            file.canonicalFile
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
        return canonicalFile.parentFile != null && canonicalFile.parentFile.exists()
    }

    /**
     * Returns `true` if a file/directory is in a given directory or in a subdirectory of the
     * given directory, and `false` otherwise. Note that this method resolves the real paths
     * of the given file/directory first via [File.getCanonicalFile].
     */
    fun isFileInDirectory(file: File, directory: File): Boolean {
        var parentFile: File?
        parentFile = try {
            file.canonicalFile.parentFile
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
        while (parentFile != null) {
            if (isSameFile(parentFile, directory)) {
                return true
            }
            parentFile = parentFile.parentFile
        }
        return false
    }

    /**
     * Returns `true` if the two files refer to the same physical file, and `false`
     * otherwise. This is the correct way to compare physical files, instead of comparing using
     * [File.equals] directly.
     *
     *
     * Unlike [java.nio.file.Files.isSameFile], this method does not require
     * the files to exist.
     *
     *
     * Internally, this method delegates to [java.nio.file.Files.isSameFile] if
     * the files exist.
     *
     *
     * If either of the files does not exist, this method instead compares the canonical files of
     * the two files, since [java.nio.file.Files.isSameFile] in some cases require
     * that the files exist and therefore cannot be used. The downside of using [ ][File.getCanonicalFile] is that it may not handle hard links and symbolic links correctly as
     * with [java.nio.file.Files.isSameFile].
     */
    fun isSameFile(file1: File, file2: File): Boolean {
        return try {
            if (file1.exists() && file2.exists()) {
                Files.isSameFile(file1.toPath(), file2.toPath())
            } else {
                file1.canonicalFile == file2.canonicalFile
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    /**
     * Creates a new [FileSystem] for a given ZIP file.
     *
     *
     * Note that NIO filesystems are unique per URI, so the returned [FileSystem] should be
     * closed as soon as possible.
     */
    @Throws(IOException::class)
    fun createZipFilesystem(archive: Path): FileSystem {
        val uri = URI.create("jar:" + archive.toUri().toString())
        return FileSystems.newFileSystem(uri, emptyMap<String, Any>())
    }
}