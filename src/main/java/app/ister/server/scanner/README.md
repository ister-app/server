

Scanning show

Start in de root van de category path
```
/diskEntity/shows/
```


Het kan in de root path hoeft het alleen naar dirs te zoeken voor dirs van shows.
```
/diskEntity/shows/Show (2023)/
/diskEntity/shows/Show2 (1923)/
```

In de show dir moet gezocht worden naar files en dirs van seizoenen.
Zoals een cover image en een metadata file.

```
/diskEntity/shows/Show (2023)/cover.jpg
/diskEntity/shows/Show (2023)/tvshow.nfo
/diskEntity/shows/Show (2023)/Season 01/
/diskEntity/shows/Show (2023)/Season 02/
```

In de seasonEntity map moet gezocht worden naar bestanden.
Dit zijn bestanden als episodeEntity mkv files.
Ook cover en subtitles files.

```
/diskEntity/shows/Show (2023)/Season 01/s01e01.mkv
/diskEntity/shows/Show (2023)/Season 01/s01e01.en.srt
/diskEntity/shows/Show (2023)/Season 01/s01e01.jpg
/diskEntity/shows/Show (2023)/Season 01/cover.jpg
```
