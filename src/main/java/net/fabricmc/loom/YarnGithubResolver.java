/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import net.fabricmc.loom.mcp.AlphaMcpConverter;
import net.fabricmc.loom.mcp.McpConverter;
import net.fabricmc.loom.mcp.McpMappingContainer;
import net.fabricmc.loom.mcp.beta.BetaMcpConverter;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.TaskDependency;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import groovy.lang.Closure;
import groovy.lang.ExpandoMetaClass;

import net.fabricmc.loom.dependencies.ComputedDependency;
import net.fabricmc.loom.providers.mappings.IMappingAcceptor;
import net.fabricmc.loom.providers.mappings.TinyReader;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.mappings.EntryTriple;

public class YarnGithubResolver {
	private static final String DEFAULT_REPO = "FabricMC/yarn";
	private static final String DOWNLOAD_URL = "https://api.github.com/repos/%s/zipball/%s";
	private static final Action<DownloadSpec> NOOP = spec -> {
	};
	final Function<Path, FileCollection> fileFactory;
	private final Path globalCache, projectCache;
	private final Supplier<File> mcJar;
	final Logger logger;

	static void createDirectory(Path path) {
		try {
			if (Files.notExists(path)) Files.createDirectories(path);
			assert Files.exists(path) && Files.isDirectory(path);
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to create directory", e);
		}
	}

	public YarnGithubResolver(Project project) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		globalCache = extension.getUserCache().toPath();
		createDirectory(globalCache);
		projectCache = extension.getRootProjectPersistentCache().toPath();
		createDirectory(projectCache);

		mcJar = () -> extension.getMinecraftProvider().getMergedJar();

		logger = project.getLogger();
		fileFactory = project::files;
	}

	public static class DownloadSpec {
		final String originalName;
		private String group, name, version, reason;
		boolean forceFresh;

		protected DownloadSpec(String name) {
			int group = name.indexOf('/');
			this.group = name.substring(0, group++);
			this.name = name.substring(group, name.indexOf('/', group));

			originalName = name;
		}

		public String getGroup() {
			return group;
		}

		public void setGroup(String group) {
			this.group = group;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getVersion() {
			//Might be able to infer the default relative to the original commit, might also be more effort than it's worth
			return version == null ? "1.0" : version;
		}

		public void setVersion(String version) {
			this.version = version;
		}

		public String getReason() {
			return reason;
		}

		public void because(String reason) {
			this.reason = reason;
		}

		public void setForceFresh(boolean force) {
			forceFresh = force;
		}
	}

	public Dependency yarnBranch(String branch) {
		return yarnBranch(branch, NOOP);
	}

	public Dependency yarnBranch(String branch, Action<DownloadSpec> action) {
		return fromBranch(DEFAULT_REPO, branch, action);
	}

	public Dependency fromBranch(String repo, String branch) {
		return fromBranch(repo, branch, NOOP);
	}

	public Dependency fromBranch(String repo, String branch, Action<DownloadSpec> action) {
		DownloadSpec spec = new DownloadSpec(repo + '/' + branch);
		action.execute(spec);
		return createFrom(spec, String.format(DOWNLOAD_URL, repo, branch));
	}

	public Dependency yarnCommit(String commit) {
		return yarnCommit(commit, NOOP);
	}

	public Dependency yarnCommit(String commit, Action<DownloadSpec> action) {
		return fromCommit(DEFAULT_REPO, commit, action);
	}

	public Dependency fromCommit(String repo, String commit) {
		return fromCommit(repo, commit, NOOP);
	}

	public Dependency fromCommit(String repo, String commit, Action<DownloadSpec> action) {
		DownloadSpec spec = new DownloadSpec(repo + '/' + commit);
		action.execute(spec);
		return createFrom(spec, String.format(DOWNLOAD_URL, repo, commit));
	}

	public BetaMcpConverter BETA = new BetaMcpConverter();
	public AlphaMcpConverter ALPHA = new AlphaMcpConverter();

	public Dependency mcp(McpConverter mcpConverter, String version, String url) {
		return mcp(mcpConverter, version, url, false);
	}

	public Dependency mcp(McpConverter mcpConverter, String version, String url, Action<McpMappingContainer> containerAction) {
		return mcp(mcpConverter, version, url, false, containerAction);
	}

	public Dependency mcp(McpConverter mcpConverter, String version, String url, boolean force) {
		return mcp(mcpConverter, version, url, force, mappings -> {});
	}

	public Dependency mcp(McpConverter mcpConverter, String version, String url, boolean force, Action<McpMappingContainer> containerAction) {
		McpMappingContainer mappings = new McpMappingContainer(version);
		containerAction.execute(mappings);

		String filename = "mcp-" + version;
		Path destination = globalCache.resolve("yarn-resolutions").resolve(filename + ".tiny.gz");
		Path mcpFile = globalCache.resolve("yarn-resolutions").resolve(filename + ".zip");
		createDirectory(destination.getParent());

		logger.debug("Creating new MCP dependency for " + url);
		class McpDep extends ComputedDependency {
			private final String url;
			private final Path mcpFile;
			private final Path destination;

			public McpDep(String group, String name, String version, String url, Path mcpFile, Path destination) {
				super(group, name, version);
				this.url = url;
				this.mcpFile = mcpFile;
				this.destination = destination;
			}

			@Override
			protected FileCollection makeFiles() {
				return fileFactory.apply(destination);
			}

			@Override
			public Set<File> resolve() {
				if (Files.exists(destination) && !force)
					return Collections.singleton(destination.toFile());

				logger.info("Resolving alpha MCP dependency for " + url);
				if (Files.notExists(destination.getParent())) throw new IllegalStateException("Dependency on " + url + " lacks a destination");

				try {
					DownloadUtil.downloadIfChanged(new URL(url), mcpFile.toFile(), logger, true);
				} catch (MalformedURLException e) {
					throw new IllegalArgumentException("Invalid origin URL: " + url, e);
				} catch (IOException e) {
					throw new RuntimeException("Unable to download " + url, e);
				}

				try {
					mcpConverter.convert(mcJar.get(), mcpFile, destination, mappings);
				} catch (IOException e) {
					throw new RuntimeException("Unable to convert from MCP", e);
				}

				return Collections.singleton(destination.toFile());
			}

			@Override
			public boolean contentEquals(Dependency dependency) {
				if (dependency == this) return true;
				if (!(dependency instanceof McpDep)) return false;
				McpDep that = (McpDep) dependency;
				return Objects.equals(url, that.url);
			}

			@Override
			public Dependency copy() {
				return new McpDep(getGroup(), getName(), getVersion(), url, mcpFile, destination);
			}
		}
		return new McpDep("de.oceanlabs", "mcp", version, url, mcpFile, destination);
	}

	public String mediafire(String htmlLink) {
		Path dest = globalCache.resolve("mediafire-cache").resolve(htmlLink.replaceAll("[^\\w\\.]", "_"));
		try {
			DownloadUtil.downloadIfChanged(new URL(htmlLink), dest.toFile(), logger, true);
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException("Invalid mediafire HTML link: " + htmlLink);
		} catch (IOException e) {
			throw new RuntimeException("Unable to download mediafire HTML", e);
		}

		String rawHtml;
		try {
			rawHtml = IOUtils.toString(new GZIPInputStream(new FileInputStream(dest.toFile())), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException("Unable to read mediafire HTML", e);
		}
		Matcher matcher = Pattern.compile("<div (?:class=\"download_link\" )?id=\"download_link\".+?href=\"(https?://[^\"]+)\"", Pattern.DOTALL).matcher(rawHtml);
		if (!matcher.find()) {
			throw new IllegalArgumentException("Unable to find download link for " + htmlLink);
		}
		return matcher.group(1);
	}

	public class GithubDependency extends ComputedDependency {
		protected final DownloadSpec spec;
		protected final String origin;
		protected final Path destination;

		public GithubDependency(DownloadSpec spec, String origin, Path destination) {
			super(spec.getGroup(), spec.getName(), spec.getVersion());
			this.spec = spec;
			this.origin = origin;
			this.destination = destination;
			because(spec.getReason());
		}

		@Override
		public boolean contentEquals(Dependency dependency) {
			if (dependency == this) return true;
			if (!(dependency instanceof GithubDependency)) return false;
			return Objects.equals(((GithubDependency) dependency).origin, origin);
		}

		@Override
		public Dependency copy() {
			return new GithubDependency(spec, origin, destination);
		}

		@Override
		public TaskDependency getBuildDependencies() {
			logger.info("Computing Github dependency's task dependency for " + spec.originalName + " from " + origin + " to " + destination);
			return super.getBuildDependencies();
		}

		@Override
		public FileCollection getFiles() {
			logger.info("Detecting Github dependency's file(s) for " + spec.originalName + " from " + origin + " to " + destination);
			return super.getFiles();
		}

		@Override
		protected FileCollection makeFiles() {
			return fileFactory.apply(destination);
		}

		@Override
		public Set<File> resolve() {
			logger.info("Resolving Github dependency for " + spec.originalName + " from " + origin + " to " + destination);
			if (Files.notExists(destination.getParent())) throw new IllegalStateException("Dependency on " + origin + " lacks a destination");

			try {
				DownloadUtil.downloadIfChanged(new URL(origin), destination.toFile(), logger, true);
				return Collections.singleton(destination.toFile());
			} catch (MalformedURLException e) {
				throw new IllegalArgumentException("Invalid origin URL: " + origin, e);
			} catch (IOException e) {
				throw new RuntimeException("Unable to download " + spec.originalName + " from " + origin, e);
			}
		}
	}

	private Dependency createFrom(DownloadSpec spec, String origin) {
		Path destination = globalCache.resolve("yarn-resolutions").resolve(spec.originalName + ".zip");
		createDirectory(destination.getParent());

		if (spec.forceFresh) {
			try {
				Files.deleteIfExists(destination);
			} catch (IOException e) {
				throw new UncheckedIOException("Unable to delete " + spec.originalName + " at " + destination, e);
			}
		}

		logger.debug("Creating new Github dependency for " + spec.originalName + " from " + origin + " to " + destination);
		return new GithubDependency(spec, origin, destination);
	}

	public Dependency extraMappings(String minecraftVersion, Action<MappingContainer> action) {
		MappingContainer mappings = new MappingContainer(minecraftVersion);
		action.execute(mappings);
		return ExtraMappings.create(projectCache.resolve("extra-mappings"), mappings, fileFactory);
	}

	public static class MappingContainer {
		public final String minecraftVersion;
		private String from = "intermediary", to = "named";
		private Map<String, String> classes = new HashMap<>();
		private Map<EntryTriple, String> methods = new HashMap<>();
		private Map<EntryTriple, String> fields = new HashMap<>();

		public MappingContainer(String minecraftVersion) {
			this.minecraftVersion = minecraftVersion;

			ExpandoMetaClass meta = new ExpandoMetaClass(MappingContainer.class, true, false);
			meta.registerInstanceMethod("class", new Closure<MappingContainer>(this) {
				private static final long serialVersionUID = -1776692419905298264L;

				@Override
				public MappingContainer call(Object... args) {
					classes.put((String) args[0], (String) args[1]);
					return null;
				}

				@Override
				public Class<?>[] getParameterTypes() {
					return new Class[] {String.class, String.class};
				}
			});
			meta.initialize();
		}

		public void setFrom(String from) {
			this.from = from;
		}

		public String getFrom() {
			return from;
		}

		public void setTo(String to) {
			this.to = to;
		}

		public String getTo() {
			return to;
		}

		public Map<String, String> getClasses() {
			return classes;
		}

		public void setClasses(Map<String, String> mappings) {
			if (mappings == null) throw new NullPointerException("Null class mappings");
			classes.clear();
			classes.putAll(mappings);
		}

		public void method(String owner, String desc, String from, String to) {
			method(new EntryTriple(owner, from, desc), to);
		}

		public void method(EntryTriple from, String to) {
			methods.put(from, to);
		}

		public Map<EntryTriple, String> getMethods() {
			return methods;
		}

		public void setMethods(Map<EntryTriple, String> mappings) {
			if (mappings == null) throw new NullPointerException("Null method mappings");
			methods.clear();
			methods.putAll(mappings);
		}

		public void field(String owner, String desc, String from, String to) {
			field(new EntryTriple(owner, from, desc), to);
		}

		public void field(EntryTriple from, String to) {
			fields.put(from, to);
		}

		public Map<EntryTriple, String> getFields() {
			return fields;
		}

		public void setFields(Map<EntryTriple, String> mappings) {
			if (mappings == null) throw new NullPointerException("Null field mappings");
			fields.clear();
			fields.putAll(mappings);
		}
	}

	public static class ExtraMappings extends ComputedDependency {
		private static class NotaGoto extends RuntimeException {
			private static final long serialVersionUID = -1268411969286315592L;
			static final NotaGoto INSTANCE = new NotaGoto();

			private NotaGoto() {//Nothing to do with exception based flow control
				setStackTrace(new StackTraceElement[0]);
			}

			@Override
			public synchronized Throwable fillInStackTrace() {
				setStackTrace(new StackTraceElement[0]);
				return this;
			}
		}
		private static volatile int instance = 1;
		private final Path output;
		private final MappingContainer mappings;
		private final Function<Path, FileCollection> fileFactory;

		public static ExtraMappings create(Path cache, MappingContainer mappings, Function<Path, FileCollection> fileFactory) {
			Hasher hasher = Hashing.murmur3_128().newHasher(); //Not totally free from collisions, but fast enough that the potential is not especially a problem

			mappings.getClasses().entrySet().stream().map(entry -> entry.getKey() + " -> " + entry.getValue()).sorted().forEach(hasher::putUnencodedChars);
			Comparator<Entry<EntryTriple, String>> memberComparator = Comparator.comparing(Entry::getKey, Comparator.comparing(EntryTriple::getOwner).thenComparing(EntryTriple::getName).thenComparing(EntryTriple::getDesc));
			mappings.getMethods().entrySet().stream().sorted(memberComparator)
					.map(entry -> entry.getKey().getOwner() + '/' + entry.getKey().getName() + entry.getKey().getDesc() + " -> " + entry.getValue()).forEach(hasher::putUnencodedChars);
			mappings.getFields().entrySet().stream().sorted(memberComparator)
					.map(entry -> entry.getKey().getOwner() + '#' + entry.getKey().getName() + " (" + entry.getKey().getDesc() + ") -> " + entry.getValue()).forEach(hasher::putUnencodedChars);

			return new ExtraMappings(hasher.hash().toString(), mappings, cache.resolve("anonymousExtra_" + instance++ + ".gz"), fileFactory);
		}

		private ExtraMappings(String name, MappingContainer mappings, Path output, Function<Path, FileCollection> fileFactory) {
			super("net.fabricmc.synthetic.extramappings", name, mappings.minecraftVersion.concat("-1"));

			this.output = output;
			this.mappings = mappings;
			this.fileFactory = fileFactory;
		}

		@Override
		public Set<File> resolve() {
			out: if (Files.exists(output)) {
				try {
					Map<String, String> classes = new HashMap<>(mappings.getClasses());
					Map<EntryTriple, String> methods = new HashMap<>(mappings.getMethods());
					Map<EntryTriple, String> fields = new HashMap<>(mappings.getFields());

					TinyReader.readTiny(output, mappings.getFrom(), mappings.getTo(), new IMappingAcceptor() {
						@Override
						public void acceptClass(String srcName, String dstName) {
							if (!classes.remove(srcName, dstName)) {
								throw NotaGoto.INSTANCE;
							}
						}

						@Override
						public void acceptMethod(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc) {
							if (!methods.remove(new EntryTriple(srcClsName, srcName, srcDesc), dstName)) {
								throw NotaGoto.INSTANCE;
							}
						}

						@Override
						public void acceptMethodArg(String srcClsName, String srcMethodName, String srcMethodDesc, int lvIndex, String dstArgName) {
							assert false; //Shouldn't be getting any of these
						}

						@Override
						public void acceptField(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc) {
							if (!fields.remove(new EntryTriple(srcClsName, srcName, srcDesc), dstName)) {
								throw NotaGoto.INSTANCE;
							}
						}
					});

					if (!classes.isEmpty() || !methods.isEmpty() || !fields.isEmpty()) break out;
				} catch (IOException e) {
					throw new RuntimeException("Error reading extra mappings from " + output, e);
				} catch (NotaGoto e) {
					break out;
				}

				return Collections.singleton(output.toFile());
			}

			createDirectory(output.getParent());
			try (TinyWriter writer = new TinyWriter(output, true, mappings.getFrom(), mappings.getTo())) {
				for (Entry<String, String> entry : mappings.getClasses().entrySet()) {
					writer.acceptClass(entry.getKey(), entry.getValue());
				}

				for (Entry<EntryTriple, String> entry : mappings.getMethods().entrySet()) {
					EntryTriple method = entry.getKey();
					writer.acceptMethod(method.getOwner(), method.getDesc(), method.getName(), entry.getValue());
				}

				for (Entry<EntryTriple, String> entry : mappings.getFields().entrySet()) {
					EntryTriple field = entry.getKey();
					writer.acceptField(field.getOwner(), field.getDesc(), field.getName(), entry.getValue());
				}

				return Collections.singleton(output.toFile());
			} catch (IOException e) {
				throw new RuntimeException("Error writing extra mappings to " + output, e);
			}
		}

		@Override
		protected FileCollection makeFiles() {
			return fileFactory.apply(output);
		}

		@Override
		public boolean contentEquals(Dependency dependency) {
			if (dependency == this) return true;
			if (!(dependency instanceof ExtraMappings)) return false;
			return Objects.equals(dependency.getName(), getName());
		}

		@Override
		public Dependency copy() {
			return new ExtraMappings(getName(), mappings, output, fileFactory);
		}
	}
}
