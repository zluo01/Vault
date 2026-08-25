SELECT path, title, file, season, episode, runtime, preview
FROM episodes
WHERE show_path = ?
ORDER BY CAST(season AS INTEGER), CAST(episode AS INTEGER)
