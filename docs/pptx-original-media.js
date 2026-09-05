(() => {
  const PPTX_URL = 'pickpico.pptx';
  const decoder = new TextDecoder('utf-8');

  function findEocd(view) {
    const min = Math.max(0, view.byteLength - 0xffff - 22);
    for (let p = view.byteLength - 22; p >= min; p -= 1) {
      if (view.getUint32(p, true) === 0x06054b50) return p;
    }
    throw new Error('ZIP end-of-central-directory not found');
  }

  async function openZip(buffer) {
    const view = new DataView(buffer);
    const eocd = findEocd(view);
    const count = view.getUint16(eocd + 10, true);
    const centralOffset = view.getUint32(eocd + 16, true);
    const entries = new Map();
    let p = centralOffset;

    for (let i = 0; i < count; i += 1) {
      if (view.getUint32(p, true) !== 0x02014b50) throw new Error('Invalid ZIP central directory');
      const method = view.getUint16(p + 10, true);
      const compressedSize = view.getUint32(p + 20, true);
      const uncompressedSize = view.getUint32(p + 24, true);
      const nameLength = view.getUint16(p + 28, true);
      const extraLength = view.getUint16(p + 30, true);
      const commentLength = view.getUint16(p + 32, true);
      const localOffset = view.getUint32(p + 42, true);
      const name = decoder.decode(new Uint8Array(buffer, p + 46, nameLength));
      entries.set(name, { method, compressedSize, uncompressedSize, localOffset });
      p += 46 + nameLength + extraLength + commentLength;
    }

    async function read(path) {
      const entry = entries.get(path);
      if (!entry) throw new Error(`PPTX entry not found: ${path}`);
      const lp = entry.localOffset;
      if (view.getUint32(lp, true) !== 0x04034b50) throw new Error(`Invalid ZIP local header: ${path}`);
      const nameLength = view.getUint16(lp + 26, true);
      const extraLength = view.getUint16(lp + 28, true);
      const dataOffset = lp + 30 + nameLength + extraLength;
      const compressed = buffer.slice(dataOffset, dataOffset + entry.compressedSize);
      if (entry.method === 0) return compressed;
      if (entry.method !== 8) throw new Error(`Unsupported ZIP compression method ${entry.method}`);
      const stream = new Blob([compressed]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
      return new Response(stream).arrayBuffer();
    }

    return { read };
  }

  function mimeFor(path) {
    if (path.endsWith('.png')) return 'image/png';
    if (path.endsWith('.jpg') || path.endsWith('.jpeg')) return 'image/jpeg';
    return 'application/octet-stream';
  }

  function croppedImage(url, crop) {
    const img = document.createElement('img');
    img.src = url;
    img.alt = '';
    img.decoding = 'async';
    img.draggable = false;
    img.style.position = 'absolute';
    img.style.maxWidth = 'none';
    img.style.pointerEvents = 'none';
    const l = (crop?.l || 0) / 100000;
    const t = (crop?.t || 0) / 100000;
    const r = (crop?.r || 0) / 100000;
    const b = (crop?.b || 0) / 100000;
    const keepX = Math.max(0.0001, 1 - l - r);
    const keepY = Math.max(0.0001, 1 - t - b);
    img.style.left = `${(-l / keepX) * 100}%`;
    img.style.top = `${(-t / keepY) * 100}%`;
    img.style.width = `${100 / keepX}%`;
    img.style.height = `${100 / keepY}%`;
    return img;
  }

  function overlay(card, url, spec) {
    if (!card) return;
    card.style.position = 'relative';
    const layer = document.createElement('div');
    layer.className = 'pptx-original-picture';
    layer.style.position = 'absolute';
    layer.style.left = `${spec.x}%`;
    layer.style.top = `${spec.y}%`;
    layer.style.width = `${spec.w}%`;
    layer.style.height = `${spec.h}%`;
    layer.style.overflow = 'hidden';
    layer.style.pointerEvents = 'none';
    layer.style.zIndex = String(spec.z || 2);
    if (spec.clipLeft) layer.style.clipPath = `inset(0 0 0 ${spec.clipLeft}%)`;
    layer.appendChild(croppedImage(url, spec.crop));
    card.appendChild(layer);
  }

  async function main() {
    try {
      const response = await fetch(PPTX_URL, { cache: 'no-store' });
      if (!response.ok) throw new Error(`Unable to load ${PPTX_URL}: ${response.status}`);
      const zip = await openZip(await response.arrayBuffer());
      const paths = [
        'ppt/media/image1.png',
        'ppt/media/image2.png',
        'ppt/media/image3.png',
        'ppt/media/image4.jpg'
      ];
      const urls = {};
      for (const path of paths) {
        const bytes = await zip.read(path);
        urls[path] = URL.createObjectURL(new Blob([bytes], { type: mimeFor(path) }));
      }

      const cards = [...document.querySelectorAll('.slide-card')];

      // Slide 1: original product render + logo. Coordinates and srcRect crop
      // come directly from the PPTX picture-frame metadata.
      overlay(cards[0], urls['ppt/media/image1.png'], {
        x: 32.665, y: 0, w: 60.689, h: 100,
        crop: { l: 4395, t: 6209, r: 23117, b: 4210 },
        clipLeft: 30,
        z: 3
      });
      overlay(cards[0], urls['ppt/media/image2.png'], {
        x: 9.431, y: 14.616, w: 19.645, h: 28.977,
        crop: { l: 0, t: 0, r: 0, b: 0 },
        z: 4
      });

      // Slide 4: original phone UI visual.
      overlay(cards[3], urls['ppt/media/image3.png'], {
        x: 63.074, y: 7.466, w: 31.801, h: 84.799,
        crop: { l: 0, t: 0, r: 0, b: 0 },
        z: 3
      });

      // Slide 6: original industrial-design board.
      overlay(cards[5], urls['ppt/media/image4.jpg'], {
        x: 40.747, y: 9.599, w: 55.105, h: 73.466,
        crop: { l: 0, t: 0, r: 0, b: 0 },
        z: 3
      });

      document.documentElement.dataset.pptxOriginalMedia = 'ready';
    } catch (error) {
      console.warn('[PickPico] Could not restore original PPTX media:', error);
      document.documentElement.dataset.pptxOriginalMedia = 'fallback';
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', main, { once: true });
  } else {
    main();
  }
})();
