// Генерация block-моделей секций оград (tip = 0, канон Fences v1.1.7-final §6.2).
// Единый источник истины для геометрии и UV: правит JSON напрямую, минуя IDE-diff.
//
// Каждая модель строится по таблице §6.2:
//   опора      X 4..12,  Y 0..16, Z 4..12
//   нити       X 7..9,   Y 4..6 и 12..14, Z 0..16
//   панель mesh/sheet X 7..9, Y 0..16, Z 0..16
//   рейка sheet X 6..10, Y 0..2,  Z 0..16
//   доски      X 7..9,   Y 2..7 и 10..15, Z 0..16
// Выступов за границу блока нет.

const fs = require('fs');
const path = require('path');

const BLOCK = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'survivalpoints', 'models', 'block');

const TEX = {
  postSide: 'survivalpoints:block/post_wood',
  postTop: 'survivalpoints:block/post_wood_top',
  plank: 'survivalpoints:block/plank',
  wire: 'survivalpoints:block/wire',
  mesh: 'survivalpoints:block/mesh',
  sheet: 'survivalpoints:block/sheet',
  metal: 'survivalpoints:block/metal',
};

// ---------- helpers ---------------------------------------------------------
// box(fx, fy, fz, tx, ty, tz, uvs) где uvs задаёт UV каждой грани.
// Для «толстой» грани толщиной 2 px: north/south = (2 x высота),
// east/west = (длина x высота), up/down = (длина x 2).
// ВАЖНО (найдено 3D-рендером): грань up/down имеет размер w x d, но если
// текстура значима только в узкой полосе (нить 2 px, доска 5 px), окно
// [0,0,w,d] захватывает пустоту → грань становится прозрачной дыркой.
// Решение из гайдлайнов §4.2: окно d x w + rotation 90 даёт эффективные w x d,
// сэмплируя ровно полосу рисунка по всей длине.
function topFace(w, d, T) {
  return { uv: [0, 0, d, w], rotation: 90, texture: T };
}

function faces(w, h, d, tex) {
  const T = '#tex';
  return {
    north: { uv: [0, 0, w, h], texture: T },
    east:  { uv: [0, 0, d, h], texture: T },
    south: { uv: [0, 0, w, h], texture: T },
    west:  { uv: [0, 0, d, h], texture: T },
    up:    topFace(w, d, T),
    down:  topFace(w, d, T),
  };
}

function element(from, to) {
  const w = to[0] - from[0];
  const h = to[1] - from[1];
  const d = to[2] - from[2];
  return { from, to, faces: faces(w, h, d) };
}

function withTex(f, textureName) {
  const out = JSON.parse(JSON.stringify(f));
  for (const k of Object.keys(out.faces)) out.faces[k].texture = textureName;
  return out;
}

function writeJson(name, obj) {
  const p = path.join(BLOCK, name);
  const text = JSON.stringify(obj, null, 2) + '\n';
  fs.writeFileSync(p, text, 'utf8');
  console.log('wrote', name);
}

function model(textures, particle, elements) {
  return {
    ambientocclusion: false,
    textures,
    elements,
  };
}

// ---------- 1. post_core ----------------------------------------------------
writeJson('post_core.json', {
  textures: {
    particle: TEX.postSide,
    side: TEX.postSide,
    top: TEX.postTop,
  },
  elements: [
    {
      from: [4, 0, 4],
      to: [12, 16, 12],
      faces: {
        north: { uv: [0, 0, 8, 16], texture: '#side' },
        east:  { uv: [0, 0, 8, 16], texture: '#side' },
        south: { uv: [0, 0, 8, 16], texture: '#side' },
        west:  { uv: [0, 0, 8, 16], texture: '#side' },
        up:    { uv: [0, 0, 8, 8],  texture: '#top' },
        down:  { uv: [0, 0, 8, 8],  texture: '#top' },
      },
    },
  ],
});

// ---------- 2. section_planks ----------------------------------------------
// Доски: X 7..9, Y 2..7 и 10..15, Z 0..16.
// UV: длинная грань (16x5) -> [0,0,16,5]; торец (2x5) -> [0,6,2,11];
//     верх/низ (2x16) -> [0,0,2,16].
function plankBoard(y0) {
  return {
    from: [7, y0, 0],
    to: [9, y0 + 5, 16],
    faces: {
      north: { uv: [0, 6, 2, 11], texture: '#plank' },
      east:  { uv: [0, 0, 16, 5], texture: '#plank' },
      south: { uv: [0, 6, 2, 11], texture: '#plank' },
      west:  { uv: [0, 0, 16, 5], texture: '#plank' },
      up:    { uv: [0, 0, 16, 2], rotation: 90, texture: '#plank' },
      down:  { uv: [0, 0, 16, 2], rotation: 90, texture: '#plank' },
    },
  };
}

writeJson('section_planks.json', model(
  { particle: TEX.plank, plank: TEX.plank },
  TEX.plank,
  [plankBoard(2), plankBoard(10)],
));

// ---------- 3. section_wire ------------------------------------------------
// Две нити: X 7..9, Y 4..6 и 12..14, Z 0..16.
// UV: длинная (16x2) -> [0,0,16,2]; торец (2x2) -> [0,0,2,2];
//     верх/низ (2x16) -> [0,0,2,16].
function wireStrand(y0) {
  return {
    from: [7, y0, 0],
    to: [9, y0 + 2, 16],
    faces: {
      north: { uv: [0, 0, 2, 2],  texture: '#wire' },
      east:  { uv: [0, 0, 16, 2], texture: '#wire' },
      south: { uv: [0, 0, 2, 2],  texture: '#wire' },
      west:  { uv: [0, 0, 16, 2], texture: '#wire' },
      // up/down: окно 16x2 + rotation 90 даёт эффективные 2x16 и сэмплит
      // ровно полосу нити — иначе грань показывает пустоту (найдено рендером).
      up:    { uv: [0, 0, 16, 2], rotation: 90, texture: '#wire' },
      down:  { uv: [0, 0, 16, 2], rotation: 90, texture: '#wire' },
    },
  };
}

writeJson('section_wire.json', model(
  { particle: TEX.wire, wire: TEX.wire },
  TEX.wire,
  [wireStrand(4), wireStrand(12)],
));

// ---------- 4. section_mesh ------------------------------------------------
writeJson('section_mesh.json', model(
  { particle: TEX.mesh, mesh: TEX.mesh },
  TEX.mesh,
  [{
    from: [7, 0, 0],
    to: [9, 16, 16],
    faces: {
      north: { uv: [0, 0, 2, 16],  texture: '#mesh' },
      east:  { uv: [0, 0, 16, 16], texture: '#mesh' },
      south: { uv: [0, 0, 2, 16],  texture: '#mesh' },
      west:  { uv: [0, 0, 16, 16], texture: '#mesh' },
      up:    { uv: [0, 0, 2, 16],  texture: '#mesh' },
      down:  { uv: [0, 0, 2, 16],  texture: '#mesh' },
    },
  }],
));

// ---------- 5. section_sheet ------------------------------------------------
// Полотно X 7..9, Y 0..16, Z 0..16 + рейка X 6..10, Y 0..2, Z 0..16.
writeJson('section_sheet.json', model(
  { particle: TEX.sheet, sheet: TEX.sheet, metal: TEX.metal },
  TEX.sheet,
  [
    {
      from: [7, 0, 0],
      to: [9, 16, 16],
      faces: {
        north: { uv: [0, 0, 2, 16],  texture: '#sheet' },
        east:  { uv: [0, 0, 16, 16], texture: '#sheet' },
        south: { uv: [0, 0, 2, 16],  texture: '#sheet' },
        west:  { uv: [0, 0, 16, 16], texture: '#sheet' },
        up:    { uv: [0, 0, 2, 16],  texture: '#sheet' },
        down:  { uv: [0, 0, 2, 16],  texture: '#sheet' },
      },
    },
    {
      from: [6, 0, 0],
      to: [10, 2, 16],
      faces: {
        north: { uv: [0, 0, 4, 2],  texture: '#metal' },
        east:  { uv: [0, 0, 16, 2], texture: '#metal' },
        south: { uv: [0, 0, 4, 2],  texture: '#metal' },
        west:  { uv: [0, 0, 16, 2], texture: '#metal' },
        up:    { uv: [0, 0, 4, 16], texture: '#metal' },
        down:  { uv: [0, 0, 4, 16], texture: '#metal' },
      },
    },
  ],
));

// ---------- 6. *_tongue ------------------------------------------------
// Языки к опоре: закрывают 4 px зазора (Z -4..0). Геометрия зеркальна
// базовой панели, но сдвинута на 4 px внутрь блока опоры.
// Модели НЕ имеют собственных граней внутрь блока (Z = 0), только наружу.
function tongue(parts) {
  return model(
    parts.textures,
    parts.particle,
    parts.elements,
  );
}

// planks tongue
writeJson('section_planks_tongue.json', model(
  { particle: TEX.plank, plank: TEX.plank },
  TEX.plank,
  [
    {
      from: [7, 2, -4],
      to: [9, 7, 0],
      faces: {
        north: { uv: [0, 6, 2, 11], texture: '#plank' },
          east:  { uv: [0, 0, 4, 5],  texture: '#plank' },
          west:  { uv: [0, 0, 4, 5],  texture: '#plank' },
          up:    { uv: [0, 0, 4, 2], rotation: 90, texture: '#plank' },
          down:  { uv: [0, 0, 4, 2], rotation: 90, texture: '#plank' },
        },
      },
      {
        from: [7, 10, -4],
        to: [9, 15, 0],
        faces: {
          north: { uv: [0, 6, 2, 11], texture: '#plank' },
          east:  { uv: [0, 0, 4, 5],  texture: '#plank' },
          west:  { uv: [0, 0, 4, 5],  texture: '#plank' },
          up:    { uv: [0, 0, 4, 2], rotation: 90, texture: '#plank' },
          down:  { uv: [0, 0, 4, 2], rotation: 90, texture: '#plank' },
      },
    },
  ],
));

// wire tongue
writeJson('section_wire_tongue.json', model(
  { particle: TEX.wire, wire: TEX.wire },
  TEX.wire,
  [4, 12].map((y0) => ({
    from: [7, y0, -4],
    to: [9, y0 + 2, 0],
    faces: {
      north: { uv: [0, 0, 2, 2],  texture: '#wire' },
      east:  { uv: [0, 0, 4, 2],  texture: '#wire' },
      west:  { uv: [0, 0, 4, 2],  texture: '#wire' },
      up:    { uv: [0, 0, 4, 2], rotation: 90, texture: '#wire' },
      down:  { uv: [0, 0, 4, 2], rotation: 90, texture: '#wire' },
    },
  })),
));

// mesh tongue
writeJson('section_mesh_tongue.json', model(
  { particle: TEX.mesh, mesh: TEX.mesh },
  TEX.mesh,
  [{
    from: [7, 0, -4],
    to: [9, 16, 0],
    faces: {
      north: { uv: [0, 0, 2, 16], texture: '#mesh' },
      east:  { uv: [0, 0, 4, 16], texture: '#mesh' },
      west:  { uv: [0, 0, 4, 16], texture: '#mesh' },
      up:    { uv: [0, 0, 2, 4],  texture: '#mesh' },
      down:  { uv: [0, 0, 2, 4],  texture: '#mesh' },
    },
  }],
));

// sheet tongue (полотно + рейка)
writeJson('section_sheet_tongue.json', model(
  { particle: TEX.sheet, sheet: TEX.sheet, metal: TEX.metal },
  TEX.sheet,
  [
    {
      from: [7, 0, -4],
      to: [9, 16, 0],
      faces: {
        north: { uv: [0, 0, 2, 16], texture: '#sheet' },
        east:  { uv: [0, 0, 4, 16], texture: '#sheet' },
        west:  { uv: [0, 0, 4, 16], texture: '#sheet' },
        up:    { uv: [0, 0, 2, 4],  texture: '#sheet' },
        down:  { uv: [0, 0, 2, 4],  texture: '#sheet' },
      },
    },
    {
      from: [6, 0, -4],
      to: [10, 2, 0],
      faces: {
        north: { uv: [0, 0, 4, 2], texture: '#metal' },
        east:  { uv: [0, 0, 4, 2], texture: '#metal' },
        west:  { uv: [0, 0, 4, 2], texture: '#metal' },
        up:    { uv: [0, 0, 4, 4], texture: '#metal' },
        down:  { uv: [0, 0, 4, 4], texture: '#metal' },
      },
    },
  ],
));

console.log('done');