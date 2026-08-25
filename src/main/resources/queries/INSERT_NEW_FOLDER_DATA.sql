INSERT INTO folders (name, position, path)
VALUES (?, (SELECT COUNT(*) FROM folders), ?)
RETURNING position;
