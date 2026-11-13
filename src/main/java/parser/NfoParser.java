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
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
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
      // the parser's own handler prints every error to the console
      builder.setErrorHandler(new DefaultHandler());
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
    if (media.isEmpty()) {
      throw new IllegalArgumentException("Fail to find media file at " + movieFolderPath);
    }
    final Map<String, String> fields = fields(root);
    String poster = fields.get("poster");
    if (poster == null && !posters.isEmpty()) {
      poster = posters.getFirst().getFileName().toString();
    }
    final Movie movie =
        new Movie(
            fields.get("title"),
            movieFolderPath,
            poster,
            fields.get("year"),
            fields.get("runtime"),
            media.getFirst().getFileName().toString());
    return new ParsedMedia(movie, tags(root));
  }

  private static ParsedMedia parseTvShow(
      final Path tvShowFolderPath, final Element root, final List<Path> posters) {
    final TvShow show =
        new TvShow(fields(root).get("title"), tvShowFolderPath, seasonPosterMap(posters));
    return new ParsedMedia(show, tags(root));
  }

  /** The last non-blank text of each direct child element, by tag name. */
  private static Map<String, String> fields(final Element root) {
    final Map<String, String> fields = new HashMap<>();
    final NodeList nodes = root.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element node) {
        text(node).ifPresent(value -> fields.put(node.getTagName(), value));
      }
    }
    return fields;
  }

  private static Map<TagCategory, List<String>> tags(final Element root) {
    final Map<TagCategory, List<String>> tags = new EnumMap<>(TagCategory.class);
    final NodeList nodes = root.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element node) {
        collectTag(node, tags);
      }
    }
    return tags;
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
    final String nfoStem = PathUtils.getBaseName(nfoFilePath);
    final String mediaFile =
        media.stream()
            .filter(m -> PathUtils.getBaseName(m).equals(nfoStem))
            .findFirst()
            .map(p -> p.getFileName().toString())
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "Fail to find media file matching nfo " + nfoFilePath));
    final String preview =
        thumbs.stream()
            .filter(p -> PathUtils.getBaseName(p).equals(nfoStem + "-thumb"))
            .findFirst()
            .map(p -> p.getFileName().toString())
            .orElse(null);

    final Map<String, String> fields = fields(root);
    final Episode episode =
        new Episode(
            fields.get("title"),
            mediaFile,
            fields.get("season"),
            fields.get("episode"),
            fields.get("runtime"),
            nfoFilePath.getParent(),
            preview);
    return new ParsedMedia(episode, Map.of());
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
