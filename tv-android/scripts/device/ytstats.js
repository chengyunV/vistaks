(() => {
  const v = document.querySelector('video')
  const p = document.querySelector('#movie_player')
  const q = v?.getVideoPlaybackQuality?.()
  let nerds = null
  try { nerds = p?.getStatsForNerds?.() } catch (e) { nerds = String(e) }
  return { size: v && [v.videoWidth, v.videoHeight], frames: q && { total: q.totalVideoFrames, dropped: q.droppedVideoFrames }, quality: p?.getPlaybackQuality?.(), levels: p?.getAvailableQualityLevels?.(), nerds: nerds && Object.fromEntries(Object.entries(nerds).filter(([k]) => /bandwidth|resolution|dropped|codecs|buffer|vct|vpb|fmt|connection/i.test(k))) }
})()
