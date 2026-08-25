package parser;

import enums.TagCategory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import model.Episode;
import model.Movie;
import model.ParsedMedia;
import model.TvShow;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import util.PathUtils;

public final class NfoParser {

  private NfoParser() {}

  public static Optional<ParsedMedia> parse(
      final Path nfoPath, final List<Path> images, final List<Path> mediaFiles) throws IOException {
    final Element rootElement = parseRootElement(nfoPath);
    final String nfoType = rootElement.getTagName();

    final ParsedMedia media =
        switch (nfoType) {
          case "movie" -> parseMovie(nfoPath.getParent(), rootElement, images, mediaFiles);
          case "tvshow" -> parseTvShow(nfoPath.getParent(), rootElement, images);
          case "episodedetails" -> parseEpisode(nfoPath, rootElement, images, mediaFiles);
          default -> throw new IOException("Unknown nfo type '" + nfoType + "' for " + nfoPath);
        };

    // Skip Blu-ray BDMV stub entries (matches the original parser).
    if (media.media().path().getFileName().toString().toLowerCase().contains("bdmv")) {
      return Optional.empty();
    }
    return Optional.of(media);
  }

  private static Element parseRootElement(final Path file) throws IOException {
    byte[] content = Files.readAllBytes(file);
    try {
      final DocumentBuilder builder = newSecureBuilder();
      final var document = builder.parse(new ByteArrayInputStream(content));
      final Element root = document.getDocumentElement();
      if (root == null) {
        throw new IOException("NFO file has no valid tag for parsing: " + file);
      }
      return root;
    } catch (SAXException e) {
      throw new IOException("Error parsing nfo file " + file, e);
    }
  }

  private static ParsedMedia parseMovie(
      final Path movieFolderPath,
      final Element root,
      final List<Path> posters,
      final List<Path> media) {
    final var builder = Movie.builder().path(movieFolderPath);
    final Map<TagCategory, List<String>> tags = new EnumMap<>(TagCategory.class);

    final var nodes = root.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      final Node child = nodes.item(i);
      if (child instanceof Element node) {
        switch (node.getTagName()) {
          case "title" -> text(node).ifPresent(builder::title);
          case "year" -> text(node).ifPresent(builder::year);
          case "runtime" -> text(node).ifPresent(builder::runtime);
          case "poster" -> text(node).ifPresent(builder::poster);
          default -> collectTag(node, tags);
        }
      }
    }
    if (!builder.isPosterSet() && !posters.isEmpty()) {
      builder.poster(posters.getFirst().getFileName().toString());
    }
    if (!media.isEmpty()) {
      builder.file(media.getFirst().getFileName().toString());
    } else {
      throw new IllegalArgumentException("Fail to find media file at " + movieFolderPath);
    }
    return new ParsedMedia(builder.build(), tags);
  }

  private static ParsedMedia parseTvShow(
      final Path tvShowFolderPath, final Element root, final List<Path> posters) {
    final var builder = TvShow.builder().path(tvShowFolderPath);
    final Map<TagCategory, List<String>> tags = new EnumMap<>(TagCategory.class);

    final var nodes = root.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      final Node child = nodes.item(i);
      if (child instanceof Element node) {
        if (node.getTagName().equals("title")) {
          text(node).ifPresent(builder::title);
        } else {
          collectTag(node, tags);
        }
      }
    }
    builder.posters(seasonPosterMap(posters));
    return new ParsedMedia(builder.build(), tags);
  }

  private static void collectTag(final Element node, final Map<TagCategory, List<String>> tags) {
    final TagCategory category =
        switch (node.getTagName()) {
          case "genre" -> TagCategory.GENRE;
          case "tag" -> TagCategory.TAG;
          case "studio" -> TagCategory.STUDIO;
          case "actor" -> TagCategory.ACTOR;
          default -> null;
        };
    if (category == null) {
      return;
    }
    final Optional<String> value = category == TagCategory.ACTOR ? actorName(node) : text(node);
    value.ifPresent(v -> tags.computeIfAbsent(category, c -> new ArrayList<>()).add(v));
  }

  private static ParsedMedia parseEpisode(
      final Path nfoFilePath, final Element root, final List<Path> thumbs, final List<Path> media) {
    if (media.isEmpty()) {
      throw new IllegalArgumentException("Media should not be empty for episode parsing.");
    }
    final var builder = Episode.builder().path(nfoFilePath.getParent());
    final var nodes = root.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      final Node child = nodes.item(i);
      if (child instanceof Element node) {
        switch (node.getTagName()) {
          case "title" -> text(node).ifPresent(builder::title);
          case "season" -> text(node).ifPresent(builder::season);
          case "episode" -> text(node).ifPresent(builder::episode);
          case "runtime" -> text(node).ifPresent(builder::runtime);
          default -> {}
        }
      }
    }

    final String nfoStem = PathUtils.getBaseName(nfoFilePath);
    final String mediaFile =
        media.stream()
            .filter(m -> PathUtils.getBaseName(m).equals(nfoStem))
            .findFirst()
            .map(p -> p.getFileName().toString())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Fail to find media file matching nfo " + nfoFilePath));
    builder.file(mediaFile);

    thumbs.stream()
        .filter(p -> PathUtils.getBaseName(p).equals(nfoStem + "-thumb"))
        .findFirst()
        .ifPresent(p -> builder.preview(p.getFileName().toString()));

    return new ParsedMedia(builder.build(), Map.of());
  }

  private static Optional<String> actorName(final Element actor) {
    final NodeList nameNodes = actor.getElementsByTagName("name");
    if (nameNodes.getLength() == 0) {
      return Optional.empty();
    }
    return text((Element) nameNodes.item(0));
  }

  private static Optional<String> text(final Element element) {
    final String content = element.getTextContent();
    if (content == null) {
      return Optional.empty();
    }
    final String trimmed = content.trim();
    return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
  }

  private static Map<String, String> seasonPosterMap(final List<Path> posters) {
    final var postersMap = new HashMap<String, String>();
    for (Path poster : posters) {
      final var fileName = poster.getFileName().toString();
      if (!fileName.contains("poster")) {
        continue;
      }
      if (fileName.startsWith("season-specials")) {
        postersMap.put("00", fileName);
      } else if (fileName.startsWith("season")) {
        final int split = fileName.indexOf('-');
        final String first = split < 0 ? fileName : fileName.substring(0, split);
        postersMap.put(first.substring("season".length()), fileName);
      } else {
        postersMap.put("main", fileName);
      }
    }
    return postersMap;
  }

  private static DocumentBuilder newSecureBuilder() throws SAXException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    try {
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder();
    } catch (Exception e) {
      throw new SAXException("Failed to configure XML parser", e);
    }
  }
}
