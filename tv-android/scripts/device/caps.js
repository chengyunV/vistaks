(async () => {
  const ask = async (codec, w, h, fps) => {
    try {
      const r = await navigator.mediaCapabilities.decodingInfo({ type: 'media-source', video: { contentType: `video/webm; codecs="${codec}"`, width: w, height: h, bitrate: 20000000, framerate: fps } })
      return `${r.supported ? 'S' : '-'}${r.smooth ? 'M' : '-'}${r.powerEfficient ? 'P' : '-'}`
    } catch (e) { return 'err' }
  }
  return {
    vp9_2160_30: await ask('vp09.00.51.08', 3840, 2160, 30),
    vp9_2160_60: await ask('vp09.00.51.08', 3840, 2160, 60),
    vp9_1440_30: await ask('vp09.00.50.08', 2560, 1440, 30),
    av1_2160_30: await ask('av01.0.12M.08', 3840, 2160, 30),
    screen: [screen.width, screen.height, devicePixelRatio],
    mse4k: MediaSource.isTypeSupported('video/webm; codecs="vp09.00.51.08"; width=3840; height=2160; framerate=30'),
  }
})()
