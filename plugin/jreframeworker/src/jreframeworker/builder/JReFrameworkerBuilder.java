package jreframeworker.builder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.LibraryLocation;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.xml.sax.SAXException;

import jreframeworker.common.RuntimeUtils;
import jreframeworker.core.BuildFile;
import jreframeworker.core.JReFrameworker;
import jreframeworker.core.JReFrameworkerProject;
import jreframeworker.engine.Engine;
import jreframeworker.engine.identifiers.DefineFinalityIdentifier;
import jreframeworker.engine.identifiers.DefineFinalityIdentifier.DefineFieldFinalityAnnotation;
import jreframeworker.engine.identifiers.DefineFinalityIdentifier.DefineMethodFinalityAnnotation;
import jreframeworker.engine.identifiers.DefineFinalityIdentifier.DefineTypeFinalityAnnotation;
import jreframeworker.engine.identifiers.DefineIdentifier;
import jreframeworker.engine.identifiers.DefineIdentifier.DefineTypeAnnotation;
import jreframeworker.engine.identifiers.DefineVisibilityIdentifier;
import jreframeworker.engine.identifiers.DefineVisibilityIdentifier.DefineFieldVisibilityAnnotation;
import jreframeworker.engine.identifiers.DefineVisibilityIdentifier.DefineMethodVisibilityAnnotation;
import jreframeworker.engine.identifiers.DefineVisibilityIdentifier.DefineTypeVisibilityAnnotation;
import jreframeworker.engine.identifiers.JREFAnnotationIdentifier;
import jreframeworker.engine.identifiers.MergeIdentifier;
import jreframeworker.engine.identifiers.MergeIdentifier.MergeTypeAnnotation;
import jreframeworker.engine.identifiers.PurgeIdentifier;
import jreframeworker.engine.identifiers.PurgeIdentifier.PurgeFieldAnnotation;
import jreframeworker.engine.identifiers.PurgeIdentifier.PurgeMethodAnnotation;
import jreframeworker.engine.identifiers.PurgeIdentifier.PurgeTypeAnnotation;
import jreframeworker.engine.utils.BytecodeUtils;
import jreframeworker.log.Log;
import jreframeworker.ui.PreferencesPage;

public class JReFrameworkerBuilder extends IncrementalProjectBuilder {
	
	private static int buildNumber = 1;
	
	public static final String BUILDER_ID = "jreframeworker.JReFrameworkerBuilder";

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.core.internal.events.InternalBuilder#build(int,java.util.Map, org.eclipse.core.runtime.IProgressMonitor)
	 */
	@SuppressWarnings("rawtypes")
	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
		if (kind == FULL_BUILD) {
			fullBuild(monitor);
		} else {
			IResourceDelta delta = getDelta(getProject());
			if (delta == null) {
				fullBuild(monitor);
			} else {
				incrementalBuild(delta, monitor);
			}
		}
		return null;
	}

	protected void clean(IProgressMonitor monitor) throws CoreException {
		JReFrameworkerProject jrefProject = getJReFrameworkerProject();
		if(jrefProject != null){
			monitor.beginTask("Cleaning: " + jrefProject.getProject().getName(), 1);
			Log.info("Cleaning: " + jrefProject.getProject().getName());
			
			// clear the Java compiler error markers (these will be fixed and restored if they remain after building phases)
			jrefProject.getProject().deleteMarkers(JavaCore.ERROR, true, IProject.DEPTH_INFINITE);

			try {
				jrefProject.clean();
				this.forgetLastBuiltState(); 
			} catch (Exception e) {
				Log.error("Error cleaning " + jrefProject.getProject().getName(), e);
			}
			jrefProject.getProject().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
			monitor.worked(1);
		} else {
			Log.warning(getProject().getName() + " is not a valid JReFrameworker project!");
		}
	}
	
	protected void fullBuild(final IProgressMonitor monitor) throws CoreException {
		Log.info("JReFrameworker Build Number: " + buildNumber++);

		JReFrameworkerProject jrefProject = getJReFrameworkerProject();
		if (jrefProject != null) {
			// make sure the build directory exists
			File projectBuildDirectory = jrefProject.getProject().getFolder(JReFrameworker.BUILD_DIRECTORY).getLocation().toFile();
			if (!projectBuildDirectory.exists()) {
				projectBuildDirectory.mkdirs();
			}
			
			// build each jref project fresh
			monitor.beginTask("Building: " + jrefProject.getProject().getName(), 1);
			Log.info("Building: " + jrefProject.getProject().getName());

//			// add each class from classes in jars in raw directory
//			// this happens before any build phases
//			File rawDirectory = jrefProject.getProject().getFolder(JReFrameworker.RAW_DIRECTORY).getLocation().toFile();
//			if(rawDirectory.exists()){
//				for(File jarFile : rawDirectory.listFiles()){
//					if(jarFile.getName().endsWith(".jar")){
//						for(Engine engine : allEngines){
//							Log.info("Embedding raw resource: " + jarFile.getName() + " in " + engine.getJarName());
//							
//							// TODO: unjar to temp directory instead?
//							File outputDirectory = new File(jarFile.getParentFile().getAbsolutePath() + File.separatorChar + "." + jarFile.getName().replace(".jar", ""));
//							JarModifier.unjar(jarFile, outputDirectory);
//							
//							// add raw class files
//							addClassFiles(engine, outputDirectory);
//							
//							// cleanup temporary directory
//							delete(outputDirectory);
//						}
//					}
//				}
//			}
			
			// discover the build phases
			File binDirectory = jrefProject.getProject().getFolder(JReFrameworker.BINARY_DIRECTORY).getLocation().toFile();
			Map<Integer,Integer> phases = null;
			try {
				phases = getNormalizedBuildPhases(binDirectory, jrefProject);
				String phasePurality = phases.size() > 1 || phases.isEmpty() ? "s" : "";
				Log.info("Discovered " + phases.size() + " explicit build phase" + phasePurality + "\nNormalized Build Phase Mapping: " + phases.toString());
				if(phases.isEmpty()){
					phases.put(1, 1); // added implicit build phase
				}
			} catch (Exception e){
				Log.error("Error determining project build phases", e);
				return;
			}
	
			try {
				LinkedList<Integer> sortedPhases = new LinkedList<Integer>(phases.keySet());
				Collections.sort(sortedPhases);
				int lastPhase = -1;
				int lastNamedPhase = -1;
				
				for(int currentPhase : sortedPhases){
					int currentNamedPhase = phases.get(currentPhase);
					boolean isFirstPhase = false;
					if(sortedPhases.getFirst().equals(currentPhase)){
						isFirstPhase = true;
					}
					boolean isLastPhase = false;
					if(sortedPhases.getLast().equals(currentPhase)){
						isLastPhase = true;
					}
					
					// map class entries to and initial modification engine sets
					Map<String, Set<Engine>> engineMap = new HashMap<String, Set<Engine>>();
					Set<Engine> allEngines = new HashSet<Engine>();

					// initialize the modification engines
					// if its the first phase then we are just initializing with the original jars
					// if its after the first phase then we are initializing with the last build phase jars
					BuildFile buildFile = jrefProject.getBuildFile();
					if(isFirstPhase){
						for(String targetJar : buildFile.getTargets()) {
							File originalJar = getClasspathJar(targetJar, jrefProject);
							if (originalJar != null && originalJar.exists()) {
								Engine engine = new Engine(originalJar, PreferencesPage.getMergeRenamingPrefix());
								allEngines.add(engine);
								for(String entry : engine.getOriginalEntries()){
									entry = entry.replace(".class", "");
									if(engineMap.containsKey(entry)){
										engineMap.get(entry).add(engine);
									} else {
										Set<Engine> engines = new HashSet<Engine>();
										engines.add(engine);
										engineMap.put(entry, engines);
									}
								}
							} else {
								Log.warning("Original Jar not found: " + targetJar);
							}
						}
					} else {
						for(String targetJar : buildFile.getTargets()) {
							File phaseJar = getBuildPhaseJar(targetJar, jrefProject, lastPhase, lastNamedPhase);
							if(!phaseJar.exists()){
								phaseJar = getClasspathJar(targetJar, jrefProject);;
							}
							if (phaseJar != null && phaseJar.exists()) {
								Engine engine = new Engine(phaseJar, PreferencesPage.getMergeRenamingPrefix());
								allEngines.add(engine);
								for(String entry : engine.getOriginalEntries()){
									entry = entry.replace(".class", "");
									if(engineMap.containsKey(entry)){
										engineMap.get(entry).add(engine);
									} else {
										Set<Engine> engines = new HashSet<Engine>();
										engines.add(engine);
										engineMap.put(entry, engines);
									}
								}
							} else {
								Log.warning("Phase Jar not found: " + targetJar);
							}
						}
					}
					
					// compute the source based jar modifications
					buildProject(binDirectory, jrefProject, engineMap, allEngines, currentPhase, currentNamedPhase);
					
					// write out the modified jars
					for(Engine engine : allEngines){
						File modifiedLibrary = getBuildPhaseJar(engine.getJarName(), jrefProject, currentPhase, currentNamedPhase);
						modifiedLibrary.getParentFile().mkdirs();
						engine.save(modifiedLibrary);

						if(isLastPhase){
							File finalModifiedLibrary = new File(projectBuildDirectory.getCanonicalPath() + File.separatorChar + engine.getJarName());
							RuntimeUtils.copyFile(modifiedLibrary, finalModifiedLibrary);
						}
						
						// log the modified runtime
						String base = jrefProject.getProject().getLocation().toFile().getCanonicalPath();
						String relativeFilePath = modifiedLibrary.getCanonicalPath().substring(base.length());
						if(relativeFilePath.charAt(0) == File.separatorChar){
							relativeFilePath = relativeFilePath.substring(1);
						}
						Log.info("Modified: " + relativeFilePath);
					}
					
					// the current build  phase is over
					lastPhase = currentPhase;
					lastNamedPhase = currentNamedPhase;
					if(currentPhase != currentNamedPhase){
						Log.info("Phase " + currentPhase + " (identified as " + currentNamedPhase + ") completed.");
					} else {
						Log.info("Phase " + currentPhase + " completed.");
					}
					
					jrefProject.refresh();
					
					// remove the java nature to prevent the Java builder from running until we are ready
					// if the build phase directory is null or does not exist then nothing was done during the phase
					File buildPhaseDirectory = getBuildPhaseDirectory(jrefProject, currentPhase, currentNamedPhase);
					if(buildPhaseDirectory != null && buildPhaseDirectory.exists()){
						jrefProject.removeJavaNature();
						for(File file : buildPhaseDirectory.listFiles()){
							if(file.getName().endsWith(".jar")){
								File modifiedLibrary = file;
								jrefProject.updateProjectLibrary(modifiedLibrary.getName(), modifiedLibrary);
							}
						}
						// restore the java nature
						jrefProject.addJavaNature();
						
						jrefProject.refresh();
					}
				}
			} catch (IOException | SAXException | ParserConfigurationException e) {
				Log.error("Error building " + jrefProject.getProject().getName(), e);
				return;
			}

			jrefProject.getProject().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
			monitor.worked(1);
		} else {
			Log.warning(getProject().getName() + " is not a valid JReFrameworker project!");
		}
	}
	
	private File getBuildPhaseDirectory(JReFrameworkerProject jrefProject, int buildPhase, int namedBuildPhase) throws IOException {
		File projectBuildDirectory = jrefProject.getProject().getFolder(JReFrameworker.BUILD_DIRECTORY).getLocation().toFile();
		String buildPhaseDirectoryName = JReFrameworker.BUILD_PHASE_DIRECTORY_PREFIX + "-" + buildPhase + "-" + namedBuildPhase;
		return new File(projectBuildDirectory.getCanonicalPath() + File.separatorChar + buildPhaseDirectoryName);
	}
	
	private File getBuildPhaseJar(String targetJar, JReFrameworkerProject jrefProject, int buildPhase, int namedBuildPhase) throws IOException {
		return new File(getBuildPhaseDirectory(jrefProject, buildPhase, namedBuildPhase).getCanonicalPath() + File.separatorChar + targetJar);
	}

	private Map<Integer,Integer> getNormalizedBuildPhases(File binDirectory, JReFrameworkerProject jrefProject) throws IOException {
		Integer[] phases = getBuildPhases(binDirectory, jrefProject);
		Map<Integer,Integer> normalizedPhases = new HashMap<Integer,Integer>();
		Integer normalizedPhase = 1;
		for(Integer phase : phases){
			normalizedPhases.put(normalizedPhase++, phase);
		}
		return normalizedPhases;
	}
	
	private Integer[] getBuildPhases(File binDirectory, JReFrameworkerProject jrefProject) throws IOException {
		Set<Integer> phases = getBuildPhases(binDirectory, jrefProject, new HashSet<Integer>());
		ArrayList<Integer> phasesSorted = new ArrayList<Integer>(phases);
		Collections.sort(phasesSorted);
		Integer[] result = new Integer[phasesSorted.size()];
		phases.toArray(result);
		return result;
	}
	
	private Set<Integer> getBuildPhases(File binDirectory, JReFrameworkerProject jrefProject, Set<Integer> phases) throws IOException {
		File[] files = binDirectory.listFiles();
		for(File file : files){
			if(file.isFile()){
				if(file.getName().endsWith(".class")){
					byte[] classBytes = Files.readAllBytes(file.toPath());
					if(classBytes.length > 0){
						try {
							ClassNode classNode = BytecodeUtils.getClassNode(classBytes);
							
							boolean purgeModification = hasPurgeModification(classNode);
							if(purgeModification){
								PurgeIdentifier purgeIdentifier = new PurgeIdentifier(classNode);
								for(PurgeTypeAnnotation purgeTypeAnnotation : purgeIdentifier.getPurgeTypeAnnotations()){
									phases.add(purgeTypeAnnotation.getPhase());
								}
								for(PurgeFieldAnnotation purgeFieldAnnotation : purgeIdentifier.getPurgeFieldAnnotations()){
									phases.add(purgeFieldAnnotation.getPhase());
								}
								for(PurgeMethodAnnotation purgeMethodAnnotation : purgeIdentifier.getPurgeMethodAnnotations()){
									phases.add(purgeMethodAnnotation.getPhase());
								}
							}
							
							boolean finalityModification = hasFinalityModification(classNode);
							if(finalityModification){
								DefineFinalityIdentifier defineFinalityIdentifier = new DefineFinalityIdentifier(classNode);
								for(DefineTypeFinalityAnnotation defineTypeFinalityAnnotation : defineFinalityIdentifier.getTargetTypes()){
									phases.add(defineTypeFinalityAnnotation.getPhase());
								}
								for(DefineFieldFinalityAnnotation defineFieldFinalityAnnotation : defineFinalityIdentifier.getTargetFields()){
									phases.add(defineFieldFinalityAnnotation.getPhase());
								}
								for(DefineMethodFinalityAnnotation defineMethodFinalityAnnotation : defineFinalityIdentifier.getTargetMethods()){
									phases.add(defineMethodFinalityAnnotation.getPhase());
								}
							}
							
							boolean visibilityModification = hasVisibilityModification(classNode);
							if(visibilityModification){
								DefineVisibilityIdentifier defineVisibilityIdentifier = new DefineVisibilityIdentifier(classNode);
								for(DefineTypeVisibilityAnnotation defineTypeVisibilityAnnotation : defineVisibilityIdentifier.getTargetTypes()){
									phases.add(defineTypeVisibilityAnnotation.getPhase());
								}
								for(DefineFieldVisibilityAnnotation defineFieldVisibilityAnnotation : defineVisibilityIdentifier.getTargetFields()){
									phases.add(defineFieldVisibilityAnnotation.getPhase());
								}
								for(DefineMethodVisibilityAnnotation defineMethodVisibilityAnnotation : defineVisibilityIdentifier.getTargetMethods()){
									phases.add(defineMethodVisibilityAnnotation.getPhase());
								}
							}
							
							boolean mergeModification = hasMergeTypeModification(classNode);
							if(mergeModification){
								MergeIdentifier mergeIdentifier = new MergeIdentifier(classNode);
								MergeTypeAnnotation mergeTypeAnnotation = mergeIdentifier.getMergeTypeAnnotation();
								phases.add(mergeTypeAnnotation.getPhase());
								// no such thing as merge field, so skipping fields
								// define field, define method, and merge method all must have the same phase as the merge type annotation
								// so we can't discover new phases by looking at the body
							}
							
							boolean defineModification = hasDefineTypeModification(classNode);
							if(defineModification){
								DefineIdentifier defineIdentifier = new DefineIdentifier(classNode);
								DefineTypeAnnotation defineTypeAnnotation = defineIdentifier.getDefineTypeAnnotation();
								phases.add(defineTypeAnnotation.getPhase());
								// define field, define method must have the same phase as the define type annotation
								// so we can't discover new phases by looking at the body
							}
						} catch (RuntimeException e){
							Log.error("Error discovering build phases...", e);
						}
					}
				}
			} else if(file.isDirectory()){
				getBuildPhases(file, jrefProject, phases);
			}
		}
		return phases;
	}

	// TODO: adding a progress monitor subtask here would be a nice feature
	private void buildProject(File binDirectory, JReFrameworkerProject jrefProject, Map<String, Set<Engine>> engineMap, Set<Engine> allEngines, int phase, int namedPhase) throws IOException {
		// make changes for each annotated class file in current directory
		File[] files = binDirectory.listFiles();
		for(File file : files){
			if(file.isFile()){
				if(file.getName().endsWith(".class")){
					byte[] classBytes = Files.readAllBytes(file.toPath());
					if(classBytes.length > 0){
						try {
							// TODO: refactor this bit to just save the parsed annotation requests instead of true/false
							ClassNode classNode = BytecodeUtils.getClassNode(classBytes);
							boolean purgeModification = hasPurgeModification(classNode);
							boolean finalityModification = hasFinalityModification(classNode);
							boolean visibilityModification = hasVisibilityModification(classNode);
							boolean mergeModification = hasMergeTypeModification(classNode);
							boolean defineModification = hasDefineTypeModification(classNode);
							
							if(purgeModification || finalityModification || visibilityModification || mergeModification || defineModification){
								// get the qualified modification class name
								String base = jrefProject.getProject().getFolder(JReFrameworker.BINARY_DIRECTORY).getLocation().toFile().getCanonicalPath();
								String modificationClassName = file.getCanonicalPath().substring(base.length());
								if(modificationClassName.charAt(0) == File.separatorChar){
									modificationClassName = modificationClassName.substring(1);
								}
								modificationClassName = modificationClassName.replace(".class", "");
							
								if(purgeModification){
									Set<String> targets = PurgeIdentifier.getPurgeTargets(classNode, namedPhase);
									for(String target : targets){
										// purge target from each jar that contains the purge target
										if(engineMap.containsKey(target)){
											for(Engine engine : engineMap.get(target)){
												if(isRuntimeJar(engine.getJarName())){
													engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader() });
												} else {
													URL[] jarURL = { new URL("jar:file:" + engine.getOriginalJar().getCanonicalPath() + "!/") };
													engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader(), URLClassLoader.newInstance(jarURL) });
												}
												engine.process(classBytes, phase, namedPhase);
											}
										} else {
											Log.warning("Class entry [" + target + "] could not be found in any of the target jars.");
										}
									}
								} 
								
								if(finalityModification){
									Set<String> targets = DefineFinalityIdentifier.getFinalityTargets(classNode, namedPhase);
									for(String target : targets){
										// merge into each target jar that contains the merge target
										if(engineMap.containsKey(target)){
											for(Engine engine : engineMap.get(target)){
												if(isRuntimeJar(engine.getJarName())){
													engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader() });
												} else {
													URL[] jarURL = { new URL("jar:file:" + engine.getOriginalJar().getCanonicalPath() + "!/") };
													engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader(), URLClassLoader.newInstance(jarURL) });
												}
												engine.process(classBytes, phase, namedPhase);
											}
										} else {
											Log.warning("Class entry [" + target + "] could not be found in any of the target jars.");
										}
									}
								} 
								
								if(visibilityModification){
									Set<String> targets = DefineVisibilityIdentifier.getVisibilityTargets(classNode, namedPhase);
									for(String target : targets){
										// merge into each target jar that contains the merge target
										if(engineMap.containsKey(target)){
											for(Engine engine : engineMap.get(target)){
												if(isRuntimeJar(engine.getJarName())){
													engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader() });
												} else {
													URL[] jarURL = { new URL("jar:file:" + engine.getOriginalJar().getCanonicalPath() + "!/") };
													engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader(), URLClassLoader.newInstance(jarURL) });
												}
												engine.process(classBytes, phase, namedPhase);
											}
										} else {
											Log.warning("Class entry [" + target + "] could not be found in any of the target jars.");
										}
									}
								}
								
								if(mergeModification){
									MergeIdentifier mergeIdentifier = new MergeIdentifier(classNode);
									MergeTypeAnnotation mergeTypeAnnotation = mergeIdentifier.getMergeTypeAnnotation();
									if(mergeTypeAnnotation.getPhase() == namedPhase){
										String target = mergeTypeAnnotation.getSupertype();
										// merge into each target jar that contains the merge target
										if(engineMap.containsKey(target)){
											for(Engine engine : engineMap.get(target)){
												if(isRuntimeJar(engine.getJarName())){
													engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader() });
												} else {
													URL[] jarURL = { new URL("jar:file:" + engine.getOriginalJar().getCanonicalPath() + "!/") };
													engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader(), URLClassLoader.newInstance(jarURL) });
												}
												engine.process(classBytes, phase, namedPhase);
											}
										} else {
											Log.warning("Class entry [" + target + "] could not be found in any of the target jars.");
										}
									}
								} 
								
								if(defineModification){
									DefineIdentifier defineIdentifier = new DefineIdentifier(classNode);
									DefineTypeAnnotation defineTypeAnnotation = defineIdentifier.getDefineTypeAnnotation();
									if(defineTypeAnnotation.getPhase() == namedPhase){
										// define or replace in every target jar
										for(Engine engine : allEngines){
											if(isRuntimeJar(engine.getJarName())){
												engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader() });
											} else {
												URL[] jarURL = { new URL("jar:file:" + engine.getOriginalJar().getCanonicalPath() + "!/") };
												engine.setClassLoaders(new ClassLoader[]{ getClass().getClassLoader(), URLClassLoader.newInstance(jarURL) });
											}
											engine.process(classBytes, phase, namedPhase);
										}
									}
								}
							}
						} catch (RuntimeException e){
							Log.error("Error modifying jar...", e);
						}
					}
				}
			} else if(file.isDirectory()){
				buildProject(file, jrefProject, engineMap, allEngines, phase, namedPhase);
			}
		}
	}
	
	// TODO: see http://help.eclipse.org/mars/index.jsp?topic=%2Forg.eclipse.platform.doc.isv%2Fguide%2FresAdv_builders.htm
	protected void incrementalBuild(IResourceDelta delta, IProgressMonitor monitor) throws CoreException {

		// for now just doing nothing, incremental build is fighting with full build and can't delete rt.jar for some reason
		
//		Log.info("Incremental Build");
		
		// TODO: implement an incremental build
//		delta.accept(new BuildDeltaVisitor());
		
//		// triggers a full rebuild
//		this.forgetLastBuiltState();
//		this.needRebuild();
		
		// directly triggers a full rebuild
//		fullBuild(monitor);
	}
	
//	private static class BuildVisitor implements IResourceVisitor {
//		@Override
//		public boolean visit(IResource resource) {
//			System.out.println(resource.getLocation().toFile().getAbsolutePath());
//			return true;
//		}
//	}
//	
//	private static class BuildDeltaVisitor implements IResourceDeltaVisitor {
//		@Override
//		public boolean visit(IResourceDelta delta) throws CoreException {
//			switch (delta.getKind()) {
//			case IResourceDelta.ADDED:
//				System.out.print("Added: ");
//				break;
//			case IResourceDelta.REMOVED:
//				System.out.print("Removed: ");
//				break;
//			case IResourceDelta.CHANGED:
//				System.out.print("Changed: ");
//				break;
//			}
//			System.out.println(delta.getFullPath().toOSString());
//			return true;
//		}
//	}
	
	/**
	 * Returns the JReFrameworker project to build or clean, if the project is invalid returns null
	 * @return
	 */
	private JReFrameworkerProject getJReFrameworkerProject(){
		IProject project = getProject();
		try {
			if(project.isOpen() && project.exists() && project.hasNature(JavaCore.NATURE_ID) && project.hasNature(JReFrameworkerNature.NATURE_ID)){
				return new JReFrameworkerProject(project);
			}
		} catch (CoreException e) {}
		return null;
	}
	
	private void delete(File f) throws IOException {
		if (f.isDirectory()){
			for (File c : f.listFiles()){
				delete(c);
			}
		}
		if (!f.delete()){
			throw new FileNotFoundException("Failed to delete file: " + f);
		}
	}
	
	private void addClassFiles(Engine engine, File f) throws IOException {
		if (f.isDirectory()){
			for (File f2 : f.listFiles()){
				addClassFiles(engine, f2);
			}
		} else if(f.getName().endsWith(".class")){
			engine.addUnprocessed(Files.readAllBytes(f.toPath()), true);
		}
	}
	
	public static File getClasspathJar(String targetJar, JReFrameworkerProject jrefProject) throws IOException, JavaModelException {
		for(IClasspathEntry classpathEntry : jrefProject.getJavaProject().getRawClasspath()){
			if(classpathEntry.getEntryKind() == IClasspathEntry.CPE_LIBRARY){
				File jar = classpathEntry.getPath().toFile().getCanonicalFile();
				if(jar.getName().equals(targetJar)){
					if(!jar.exists()){
						// path may have been relative, so try again to resolve path relative to project directory
						String relativePath = jar.getAbsolutePath();
						String projectName = jrefProject.getProject().getName();
						String projectRoot = File.separator + projectName;
						relativePath = relativePath.substring(relativePath.indexOf(projectRoot) + projectRoot.length());
						jar = jrefProject.getProject().getFile(relativePath).getLocation().toFile();
						if(!jar.exists()){
							// if jar still doesn't exist match any jar in the project with the same name
							jar = jrefProject.getProject().getFile(jar.getName()).getLocation().toFile();
						}
					}
					return jar;
				}
			}
		}
		return getOriginalRuntimeJar(targetJar);
	}

	private boolean isRuntimeJar(String jar) throws IOException {
		IVMInstall vmInstall = JavaRuntime.getDefaultVMInstall();
		LibraryLocation[] locations = JavaRuntime.getLibraryLocations(vmInstall);
		for (LibraryLocation library : locations) {
			File runtime = JavaCore.newLibraryEntry(library.getSystemLibraryPath(), null, null).getPath().toFile().getCanonicalFile();
			if(runtime.getName().equals(jar)){
				return true;
			}
		}
		return false;
	}
	
	private static File getOriginalRuntimeJar(String targetJar) throws IOException {
		IVMInstall vmInstall = JavaRuntime.getDefaultVMInstall();
		LibraryLocation[] locations = JavaRuntime.getLibraryLocations(vmInstall);
		for (LibraryLocation library : locations) {
			File runtime = JavaCore.newLibraryEntry(library.getSystemLibraryPath(), null, null).getPath().toFile().getCanonicalFile();
			if(runtime.getName().equals(targetJar)){
				return runtime;
			}
		}
		return null;
	}
	
	private static boolean hasMergeTypeModification(ClassNode classNode) throws IOException {
		// TODO: address innerclasses, classNode.innerClasses, could these even be found from class files? they would be different files...
		if(classNode.invisibleAnnotations != null){
			for(Object annotationObject : classNode.invisibleAnnotations){
				AnnotationNode annotationNode = (AnnotationNode) annotationObject;
				JREFAnnotationIdentifier checker = new JREFAnnotationIdentifier();
				checker.visitAnnotation(annotationNode.desc, false);
				if(checker.isMergeTypeAnnotation()){
					return true;
				}
			}
		}
		return false;
	}
	
	private static boolean hasDefineTypeModification(ClassNode classNode) throws IOException {
		// TODO: address innerclasses, classNode.innerClasses, could these even be found from class files? they would be different files...
		if(classNode.invisibleAnnotations != null){
			for(Object annotationObject : classNode.invisibleAnnotations){
				AnnotationNode annotationNode = (AnnotationNode) annotationObject;
				JREFAnnotationIdentifier checker = new JREFAnnotationIdentifier();
				checker.visitAnnotation(annotationNode.desc, false);
				if(checker.isDefineTypeAnnotation()){
					return true;
				}
			}
		}
		return false;
	}
	
	private static boolean hasPurgeModification(ClassNode classNode) throws IOException {
		// TODO: address innerclasses, classNode.innerClasses, could these even be found from class files? they would be different files...
		if(classNode.invisibleAnnotations != null){
			for(Object annotationObject : classNode.invisibleAnnotations){
				AnnotationNode annotationNode = (AnnotationNode) annotationObject;
				JREFAnnotationIdentifier checker = new JREFAnnotationIdentifier();
				checker.visitAnnotation(annotationNode.desc, false);
				if(checker.isPurgeAnnotation()){
					return true;
				}
			}
		}
		return false;
	}
	
	private static boolean hasFinalityModification(ClassNode classNode) throws IOException {
		// TODO: address innerclasses, classNode.innerClasses, could these even be found from class files? they would be different files...
		if(classNode.invisibleAnnotations != null){
			for(Object annotationObject : classNode.invisibleAnnotations){
				AnnotationNode annotationNode = (AnnotationNode) annotationObject;
				JREFAnnotationIdentifier checker = new JREFAnnotationIdentifier();
				checker.visitAnnotation(annotationNode.desc, false);
				if(checker.isFinalityAnnotation()){
					return true;
				}
			}
		}
		return false;
	}
	
	private static boolean hasVisibilityModification(ClassNode classNode) throws IOException {
		// TODO: address innerclasses, classNode.innerClasses, could these even be found from class files? they would be different files...
		if(classNode.invisibleAnnotations != null){
			for(Object annotationObject : classNode.invisibleAnnotations){
				AnnotationNode annotationNode = (AnnotationNode) annotationObject;
				JREFAnnotationIdentifier checker = new JREFAnnotationIdentifier();
				checker.visitAnnotation(annotationNode.desc, false);
				if(checker.isVisibilityAnnotation()){
					return true;
				}
			}
		}
		return false;
	}
}
