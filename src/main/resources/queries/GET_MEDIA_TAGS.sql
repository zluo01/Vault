SELECT category, name
FROM tags
WHERE path = ?
ORDER BY category, name;
