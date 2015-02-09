package io.github.appbundler;

/*
 * Copyright 2014, Takashi AOKI and other contributors.
 * Copyright 2001-2008 The Codehaus.
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

import io.github.appbundler.encoding.DefaultEncodingDetector;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.velocity.VelocityComponent;

/**
 * Package dependencies as an Application Bundle for Mac OS X.
 *
 * @goal bundle
 * @phase package
 * @requiresDependencyResolution runtime
 */
public class CreateApplicationBundleMojo extends AbstractMojo
{

    /**
     * Default includes - everything is included.
     */
    private static final String[] DEFAULT_INCLUDES = {"**/**"};

    /**
     * The Maven Project Object
     *
     * @parameter default-value="${project}"
     * @readonly
     */
    private MavenProject project;

    /**
     * The directory where the application bundle will be created
     *
     * @parameter default-value="${project.build.directory}/${project.build.finalName}";
     */
    private File buildDirectory;

    /**
     * The name of the launcher binary.
     * This is the name that will appear in Activity Monitor, the Application Switcher, etc.
     *
     * @parameter default-value="JavaAppLauncher";
     */
    private String javaLauncherName;

    /**
     * The main class to execute when double-clicking the Application Bundle
     *
     * @parameter expression="${mainClass}"
     * @required
     */
    private String mainClass;

    /**
     * The name of the Bundle. This is the name that is given to the application bundle;
     * and it is also what will show up in the application menu, dock etc.
     *
     * @parameter default-value="${project.name}"
     * @required
     */
    private String bundleName;

    /**
     * The path to the working directory.  This can be inside or outside the app bundle.  To
     * define a working directory <b>inside</b> the app bundle, use e.g. <code>$APP_ROOT</code>.
     *
     * @parameter default-value="$APP_ROOT"
     */
    private String workingDirectory;


    /**
     * The icon file for the bundle
     *
     * @parameter
     */
    private String iconFile;

    /**
     * The version of the project. Will be used as the value of the CFBundleVersion key.
     *
     * @parameter default-value="${project.version}"
     */
    private String version;

    /**
     * A value for the JVMVersion key.
     *
     * @parameter default-value="1.4+"
     */
    private String jvmVersion;

    /**
     * Application arguments for the JVMArguments key.
     *
     * @parameter
     */
    private List<String> jvmArguments;

    /**
     * Paths to be put on the classpath in addition to the projects dependencies.
     * Might be useful to specifiy locations of dependencies in the provided scope that are not distributed with
     * the bundle but have a known location on the system.
     * {@see http://jira.codehaus.org/browse/MOJO-874}
     *
     * @parameter
     */
    private List<String> additionalClasspath;

    /**
     * Additional resources (as a list of FileSet objects) that will be copies into
     * the build directory and included in the .dmg and zip files alongside with the
     * application bundle.
     *
     * @parameter
     */
    private List<FileSet> additionalResources;

    /**
     * Additional files to bundle inside the Resources/Java directory and include on
     * the classpath.  These could include additional JARs or JNI libraries.
     *
     * @parameter
     */
    private List<FileSet> additionalBundledClasspathResources;

    /**
     * Velocity Component.
     *
     * @component
     * @readonly
     */
    private VelocityComponent velocity;

    /**
     * The location of the template for Info.plist.
     * Classpath is checked before the file system.
     *
     * @parameter default-value="io/github/appbundler/Info.plist.template"
     */
    private String dictionaryFile;

    /**
     * Options to the JVM, will be used as the value of VMOptions in Info.plist.
     *
     * @parameter
     */
    private String vmOptions;

    private static String TARGET_CLASS_ROOT = "target" + File.separator + "classes";

    /**
     * Bundle project as a Mac OS X application bundle.
     *
     * @throws MojoExecutionException If an unexpected error occurs during packaging of the bundle.
     */
    public void execute() throws MojoExecutionException
    {
        // Set up and create directories
        buildDirectory.mkdirs();

        File bundleDir = new File(buildDirectory, bundleName + ".app");
        bundleDir.mkdirs();

        File contentsDir = new File(bundleDir, "Contents");
        contentsDir.mkdirs();

        File resourcesDir = new File(contentsDir, "Resources");
        resourcesDir.mkdirs();

        File javaDirectory = new File(contentsDir, "Java");
        javaDirectory.mkdirs();

        File macOSDirectory = new File(contentsDir, "MacOS");
        macOSDirectory.mkdirs();

        // Copy in the native java application stub
        File launcher = new File(macOSDirectory, javaLauncherName);
        launcher.setExecutable(true);

        FileOutputStream launcherStream = null;

        try {
            launcherStream = new FileOutputStream(launcher);
        } catch (FileNotFoundException e) {
            throw new MojoExecutionException("Could not copy file to directory " + launcher, e);
        }

        InputStream launcherResourceStream = this.getClass().getResourceAsStream(javaLauncherName);
        try {
            copyStream(launcherResourceStream, launcherStream);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not copy file " + javaLauncherName + " to directory " + macOSDirectory, e);
        }

        // Copy icon file to the bundle if specified
        if (iconFile != null) {
            File f = new File(TARGET_CLASS_ROOT, iconFile);

            if (f.exists() && f.isFile()) {
                try {
                    FileUtils.copyFileToDirectory(f, resourcesDir);
                }
                catch ( IOException e ) {
                    throw new MojoExecutionException("Error copying file " + iconFile + " to " + resourcesDir, e);
                }
            }
        }

        // Resolve and copy in all dependencies from the pom
        List<String> files = copyDependencies(javaDirectory);

        System.out.println("Checking for additionalBundledClasspathResources: " + additionalBundledClasspathResources);
        if(additionalBundledClasspathResources != null && !additionalBundledClasspathResources.isEmpty()) {
            files.addAll(copyAdditionalBundledClasspathResources(javaDirectory, "lib", additionalBundledClasspathResources));
        }

        // Create and write the Info.plist file
        File infoPlist = new File(bundleDir, "Contents" + File.separator + "Info.plist");
        writeInfoPlist(infoPlist, files);

        // Copy specified additional resources into the top level directory
        if (additionalResources != null && !additionalResources.isEmpty()) {
            copyResources(buildDirectory, additionalResources);
        }
    }

    /**
     * The bundle name is used in paths, so we need to clean it for
     * unwanted characters, like ":" on Windows.
     * @param bundleName the "unclean" bundle name.
     * @return a clean bundle name
     */
    private String cleanBundleName(String bundleName) {
        return bundleName.replace(':', '-');
    }

    /**
     * Copy all dependencies into the $JAVAROOT directory
     *
     * @param javaDirectory where to put jar files
     * @return A list of file names added
     * @throws MojoExecutionException
     */
    private List<String> copyDependencies(File javaDirectory)
        throws MojoExecutionException
    {

        ArtifactRepositoryLayout layout = new DefaultRepositoryLayout();

        List<String> list = new ArrayList<String>();

        // First, copy the project's own artifact
        File artifactFile = project.getArtifact().getFile();
        list.add(layout.pathOf(project.getArtifact()));

        try
        {
            FileUtils.copyFile( artifactFile, new File(javaDirectory, layout.pathOf(project.getArtifact())) );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Could not copy artifact file " + artifactFile + " to " + javaDirectory );
        }

        Set<Artifact> artifacts = project.getArtifacts();

        Iterator<Artifact> i = artifacts.iterator();

        while ( i.hasNext() )
        {
            Artifact artifact = i.next();

            File file = artifact.getFile();
            File dest = new File(javaDirectory, layout.pathOf(artifact));

            getLog().debug("Adding " + file);

            try
            {
                FileUtils.copyFile( file, dest);
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException("Error copying file " + file + " into " + javaDirectory, e);
            }

            list.add(layout.pathOf(artifact));
        }

        return list;
    }

    /**
     * Copy additional dependencies into the $JAVAROOT directory.
     * @param javaDirectory
     * @param targetDirectoryName The directory within $JAVAROOT that these resources will be copied to
     * @param additionalBundledClasspathResources
     * @return A list of file names added
     * @throws MojoExecutionException
     */
    private List<String> copyAdditionalBundledClasspathResources(File javaDirectory, String targetDirectoryName, List<FileSet> additionalBundledClasspathResources) throws MojoExecutionException {
        // Create the destination directory
        File destinationDirectory = new File(javaDirectory, targetDirectoryName);
        destinationDirectory.mkdirs();

        List<String> addedFilenames = copyResources(destinationDirectory, additionalBundledClasspathResources);

        return addPath(addedFilenames, targetDirectoryName);
    }

    /**
     * Modifies a String list of filenames to include an additional path.
     * @param filenames
     * @param additionalPath
     * @return
     */
    private List<String> addPath(List<String> filenames, String additionalPath) {
        ArrayList<String> newFilenames = new ArrayList<String>(filenames.size());
        for (int i = 0; i < filenames.size(); i++) {
            newFilenames.add(additionalPath + '/' + filenames.get(i));
        }
        return newFilenames;
    }

    /**
     * Writes an Info.plist file describing this bundle.
     *
     * @param infoPlist The file to write Info.plist contents to
     * @param files     A list of file names of the jar files to add in $JAVAROOT
     * @throws MojoExecutionException
     */
    private void writeInfoPlist(File infoPlist, List<String> files) throws MojoExecutionException {
        Velocity.setProperty("file.resource.loader.path", TARGET_CLASS_ROOT);

        try {
            Velocity.init();
        } catch (Exception e) {
            throw new MojoExecutionException( "Exception occured in initializing velocity", e);
        }

        VelocityContext velocityContext = new VelocityContext();

        velocityContext.put("mainClass", mainClass);
        velocityContext.put("cfBundleExecutable", javaLauncherName);
        velocityContext.put("vmOptions", vmOptions);
        velocityContext.put("bundleName", cleanBundleName(bundleName));
        velocityContext.put("workingDirectory", workingDirectory);

        if (iconFile == null) {
            velocityContext.put("iconFile", "GenericJavaApp.icns");
        } else {
            File icon = new File(TARGET_CLASS_ROOT, iconFile);
            velocityContext.put("iconFile", (icon.exists() && icon.isFile()) ? icon.getName() : "GenericJavaApp.icns");
        }

        velocityContext.put("version", version);

        velocityContext.put("jvmVersion", jvmVersion);

        StringBuffer jarFilesBuffer = new StringBuffer();

        jarFilesBuffer.append("<array>");

        for (int i = 0; i < files.size(); i++ ) {
            String name = (String) files.get(i);
            jarFilesBuffer.append("<string>");
            jarFilesBuffer.append(name);
            jarFilesBuffer.append("</string>");
        }

        if ( additionalClasspath != null ) {
            for ( int i = 0; i < additionalClasspath.size(); i++ ) {
                String pathElement = (String) additionalClasspath.get( i );
                jarFilesBuffer.append("<string>");
                jarFilesBuffer.append(pathElement);
                jarFilesBuffer.append("</string>");
            }
        }

        jarFilesBuffer.append("</array>");

        velocityContext.put("classpath", jarFilesBuffer.toString());

        StringBuilder jvmArgumentsBuffer = new StringBuilder();

        jvmArgumentsBuffer.append("<array>");

        if ( jvmArguments != null ) {
            for ( int i = 0; i < jvmArguments.size(); i++ ) {
                String argumentElement = (String) jvmArguments.get( i );
                jvmArgumentsBuffer.append("<string>");
                jvmArgumentsBuffer.append(argumentElement);
                jvmArgumentsBuffer.append("</string>");
            }
        }

        jvmArgumentsBuffer.append("</array>");

        velocityContext.put("jvmArguments", jvmArgumentsBuffer.toString());

        try {
            File f = new File(TARGET_CLASS_ROOT, dictionaryFile);
            URI rsrc = null;

            if(f.exists() && f.isFile()) {
                rsrc = f.toURI();

                String encoding = detectEncoding(rsrc);

                getLog().debug("Detected encoding " + encoding + " for dictionary file " + dictionaryFile);

                Writer writer = new OutputStreamWriter(new FileOutputStream(infoPlist), encoding);

                Template template = Velocity.getTemplate(dictionaryFile, encoding);

                template.merge(velocityContext, writer);

                writer.close();
            } else {
                Writer writer = new OutputStreamWriter(new FileOutputStream(infoPlist), "UTF-8");

                velocity.getEngine().mergeTemplate(dictionaryFile, "UTF-8", velocityContext, writer);

                writer.close();
            }
        }
        catch ( IOException e ) {
            throw new MojoExecutionException("Could not write Info.plist to file " + infoPlist, e);
        }
        catch ( ParseErrorException e ) {
            throw new MojoExecutionException("Error parsing " + dictionaryFile, e);
        }
        catch ( ResourceNotFoundException e ) {
            throw new MojoExecutionException("Could not find resource for template " + dictionaryFile, e);
        }
        catch ( MethodInvocationException e ) {
            throw new MojoExecutionException("MethodInvocationException occured merging Info.plist template " + dictionaryFile, e);
        }
        catch ( Exception e ) {
            throw new MojoExecutionException("Exception occured merging Info.plist template " + dictionaryFile, e);
        }
    }

    private static String detectEncoding( URI uri ) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(uri));
        return new DefaultEncodingDetector().detectXmlEncoding( new ByteArrayInputStream(data) );
    }

    /**
     * Scan a fileset and get a list of files which it contains.
     * @param fileset
     * @return list of files contained within a fileset.
     * @throws FileNotFoundException
     */
    private List<String> scanFileSet(File sourceDirectory, FileSet fileSet) {
        final String[] emptyStrArray = {};

        DirectoryScanner scanner = new DirectoryScanner();

        scanner.setBasedir( sourceDirectory );
        if ( fileSet.getIncludes() != null && !fileSet.getIncludes().isEmpty() )
        {
            scanner.setIncludes( fileSet.getIncludes().toArray( emptyStrArray ) );
        }
        else
        {
            scanner.setIncludes( DEFAULT_INCLUDES );
        }

        if ( fileSet.getExcludes() != null && !fileSet.getExcludes().isEmpty() )
        {
            scanner.setExcludes( fileSet.getExcludes().toArray( emptyStrArray ) );
        }

        if (fileSet.isUseDefaultExcludes())
        {
            scanner.addDefaultExcludes();
        }

        scanner.scan();

        List<String> includedFiles = Arrays.asList( scanner.getIncludedFiles() );

        return includedFiles;
    }

    /**
     * Copies given resources to the build directory.
     *
     * @param fileSets A list of FileSet objects that represent additional resources to copy.
     * @throws MojoExecutionException In case af a resource copying error.
     */
    private List<String> copyResources(File targetDirectory, List<FileSet> fileSets) throws MojoExecutionException {
        ArrayList<String> addedFiles = new ArrayList<String>();
        for ( Iterator<FileSet> it = fileSets.iterator(); it.hasNext(); )
        {
            FileSet fileSet = (FileSet) it.next();

            // Get the absolute base directory for the FileSet
            File sourceDirectory = new File(fileSet.getDirectory());

            if (!sourceDirectory.isAbsolute()) {
                sourceDirectory = new File(project.getBasedir(), sourceDirectory.getPath());
            }

            if (!sourceDirectory.exists()) {
                // If the requested directory does not exist, log it and carry on
                // TODO re-instate the logging that was here previously
                continue;
            }

            List<String> includedFiles = scanFileSet(sourceDirectory, fileSet);
            addedFiles.addAll(includedFiles);

            getLog().info( "Copying " + includedFiles.size() + " additional resource"
                           + ( includedFiles.size() > 1 ? "s" : "" ) );

            for ( Iterator<String> j = includedFiles.iterator(); j.hasNext(); )
            {
                String destination = (String) j.next();
                File source = new File(sourceDirectory, destination);
                File destinationFile = new File(targetDirectory, destination);

                // Make sure that the directory we are copying into exists
                destinationFile.getParentFile().mkdirs();

                try {
                    FileUtils.copyFile(source, destinationFile);
                } catch (IOException e) {
                    throw new MojoExecutionException("Error copying additional resource " + source, e);
                }
            }
        }
        return addedFiles;
    }

    private static boolean copyStream(final InputStream is, final OutputStream os) throws IOException {
        try {
            final byte[] buf = new byte[1024];

            int len = 0;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
            is.close();
            os.close();
            return true;
        } catch (final IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
