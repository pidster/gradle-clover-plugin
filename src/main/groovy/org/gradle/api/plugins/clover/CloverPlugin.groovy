/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.plugins.clover

import java.lang.reflect.Constructor
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.internal.AsmBackedClassGenerator
import org.gradle.api.plugins.GroovyPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * <p>A {@link org.gradle.api.Plugin} that provides a task for creating a code coverage report using Clover.</p>
 *
 * @author Benjamin Muschko
 */
class CloverPlugin implements Plugin<Project> {
    private static final Logger log = LoggerFactory.getLogger(CloverPlugin)
    static final String GENERATE_REPORT_TASK_NAME = 'cloverGenerateReport'
    static final String JAVA_INCLUDES = '**/*.java'
    static final String GROOVY_INCLUDES = '**/*.groovy'

    @Override
    void apply(Project project) {
        project.plugins.apply(JavaPlugin)

        CloverPluginConvention cloverPluginConvention = new CloverPluginConvention()
        project.convention.plugins.clover = cloverPluginConvention

        configureGenerateCoverageReportTask(project, cloverPluginConvention)
        configureTestTask(project, cloverPluginConvention)
    }

    private void configureTestTask(Project project, CloverPluginConvention cloverPluginConvention) {
        AsmBackedClassGenerator generator = new AsmBackedClassGenerator()
        Class<? extends InstrumentCodeAction> instrumentClass = generator.generate(InstrumentCodeAction)
        Constructor<InstrumentCodeAction> constructor = instrumentClass.getConstructor()

        InstrumentCodeAction instrument = constructor.newInstance()
        instrument.conventionMapping.map('compileGroovy') {
            hasGroovyPlugin(project)
        }
        instrument.conventionMapping.map('classpath') {
            project.configurations.testRuntime.asFileTree
        }
        instrument.conventionMapping.map('classesBackupDir') {
            getClassesBackupDirectory(project, cloverPluginConvention)
        }
        instrument.conventionMapping.map('licenseFile') {
            getLicenseFile(project, cloverPluginConvention)
        }
        instrument.conventionMapping.map('classesDir') {
            project.sourceSets.main.classesDir
        }
        instrument.conventionMapping.map('srcDirs') {
            getSourceDirectories(project)
        }
        instrument.conventionMapping.map('sourceCompatibility') {
            project.sourceCompatibility?.toString()
        }
        instrument.conventionMapping.map('targetCompatibility') {
            project.targetCompatibility?.toString()
        }
        instrument.conventionMapping.map('includes') {
            getIncludes(project, cloverPluginConvention)
        }
        instrument.conventionMapping.map('excludes') {
            cloverPluginConvention.excludes
        }

        project.gradle.taskGraph.whenReady { TaskExecutionGraph graph ->
            def generateReportTask = project.tasks.getByName(GENERATE_REPORT_TASK_NAME)

            // Only invoke instrumentation when Clover report generation task is run
            if(graph.hasTask(generateReportTask)) {
                project.tasks.withType(Test).each { Test test ->
                    test.doFirst instrument
                }
            }
        }
    }

    private void configureGenerateCoverageReportTask(Project project, CloverPluginConvention cloverPluginConvention) {
        project.tasks.withType(GenerateCoverageReportTask).whenTaskAdded { GenerateCoverageReportTask generateCoverageReportTask ->
            generateCoverageReportTask.dependsOn project.tasks.withType(Test)
            generateCoverageReportTask.conventionMapping.map('classesDir') { project.sourceSets.main.classesDir }
            generateCoverageReportTask.conventionMapping.map('classesBackupDir') { getClassesBackupDirectory(project, cloverPluginConvention) }
            generateCoverageReportTask.conventionMapping.map('reportsDir') { project.reportsDir }
            generateCoverageReportTask.conventionMapping.map('classpath') { project.configurations.testRuntime.asFileTree }
            generateCoverageReportTask.conventionMapping.map('licenseFile') { getLicenseFile(project, cloverPluginConvention) }
            generateCoverageReportTask.conventionMapping.map('targetPercentage') { cloverPluginConvention.targetPercentage }
            generateCoverageReportTask.conventionMapping.map('xml') { cloverPluginConvention.report.xml }
            generateCoverageReportTask.conventionMapping.map('json') { cloverPluginConvention.report.json }
            generateCoverageReportTask.conventionMapping.map('html') { cloverPluginConvention.report.html }
            generateCoverageReportTask.conventionMapping.map('pdf') { cloverPluginConvention.report.pdf }
        }

        GenerateCoverageReportTask generateCoverageReportTask = project.tasks.add(GENERATE_REPORT_TASK_NAME, GenerateCoverageReportTask)
        generateCoverageReportTask.description = 'Generates Clover code coverage report.'
        generateCoverageReportTask.group = 'report'
    }

    private File getClassesBackupDirectory(Project project, CloverPluginConvention cloverPluginConvention) {
        cloverPluginConvention.classesBackupDir ?: new File("${project.sourceSets.main.classesDir}-bak")
    }

    private File getLicenseFile(Project project, CloverPluginConvention cloverPluginConvention) {
        cloverPluginConvention.licenseFile ?: new File(project.rootDir, 'clover.license')
    }

    /**
     * Gets source directories. If the Groovy plugin was applied we only its source directories in addition to the
     * Java plugin source directories. We only add directories that actually exist.
     *
     * @param project Project
     * @return Source directories
     */
    private Set<File> getSourceDirectories(Project project) {
        def srcDirs = [] as Set<File>

        if(hasGroovyPlugin(project)) {
            addExistingSourceDirectories(srcDirs, project.sourceSets.main.java.srcDirs)
            addExistingSourceDirectories(srcDirs, project.sourceSets.main.groovy.srcDirs)
        }
        else {
            addExistingSourceDirectories(srcDirs, project.sourceSets.main.java.srcDirs)
        }

        srcDirs
    }

    /**
     * Adds source directories to target Set only if they actually exist.
     *
     * @param target Target
     * @param source Source
     */
    private void addExistingSourceDirectories(Set<File> target, Set<File> source) {
        source.each {
            if(it.exists()) {
                target << it
            }
            else {
                log.warn "The specified source directory '$it.canonicalPath' does not exist. It won't be included in Clover instrumentation."
            }
        }
    }

    /**
     * Gets includes for compilation. Uses includes if set as convention property. Otherwise, use default includes. The
     * default includes are determined by the fact if Groovy plugin was applied to project or not.
     *
     * @param project Project
     * @param cloverPluginConvention Clover plugin convention
     * @return Includes
     */
    private List getIncludes(Project project, CloverPluginConvention cloverPluginConvention) {
        if(cloverPluginConvention.includes) {
            return cloverPluginConvention.includes
        }

        if(hasGroovyPlugin(project)) {
            return [JAVA_INCLUDES, GROOVY_INCLUDES]
        }

        [JAVA_INCLUDES]
    }

    private boolean hasGroovyPlugin(Project project) {
        project.plugins.hasPlugin(GroovyPlugin)
    }
}