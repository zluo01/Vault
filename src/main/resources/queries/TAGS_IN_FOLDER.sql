SELECT DISTINCT tags.category, tags.name
FROM tags
         JOIN media ON tags.path = media.path
         JOIN folders ON media.folder = folders.name
WHERE folders.position = ?
ORDER BY tags.category, tags.name;
