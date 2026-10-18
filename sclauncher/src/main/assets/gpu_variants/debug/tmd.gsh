#version 330 core
// DEBUG probe v3 for RG406H (Mali-G57) invisible-TMD investigation.
//
// v2 result (1.20_yellow): viewspaceZ reads tiny-nonzero (< 1.0) for nearly
// all primitives, zfar sane (no magenta) => the VS->GS interface-block
// varying transport is broken on this driver, while gl_in builtins and UBO
// reads appear fine and positions/emit work.
//
// v3 tests the candidate launcher-side-only fix: recompute view-space Z in
// the GS from the (trustworthy) clip positions + inverse(projection) taken
// from the shared transforms UBO, then bucket the recovered z exactly like
// v2 for direct comparison, plus an ORANGE guard for a dead transforms UBO:
//   ORANGE  (1,0.5,0) = projection diagonal reads zero => transforms UBO
//             unreadable in GS => GS-side workaround impossible, CPU
//             de-adjacency (game change) is the only path
//   MAGENTA (1,0,1) = zfar > 10000  => projectionInfo UBO garbage
//   RED     (1,0,0) = recovered z all exactly 0.0
//   YELLOW  (1,1,0) = recovered min z in (0, 1.0)
//   CYAN    (0,1,1) = recovered min z in [1.0, zfar*0.5]
//   WHITE   (1,1,1) = recovered min z > zfar*0.5 (survives stock cull)
// If v3 shows a sane WHITE/CYAN distribution where v2 was all YELLOW, the
// recovered-z cull is the fix (v4 = real varyings + recovered-z cull).
// w <= 0 (behind camera) forces z = -1 (stock culls z <= 0 the same way).
layout (triangles_adjacency) in;
layout (triangle_strip, max_vertices = 3) out;

in VS_OUT {
  smooth vec2 vertUv;
  flat vec2 vertTpage;
  flat vec2 vertClut;
  flat int vertBpp;
  smooth vec4 vertColour;
  flat int vertFlags;

  flat int translucency;

  flat float widthMultiplier;
  flat int widthMask;
  flat int indexShift;
  flat int indexMask;

  smooth float viewspaceZ;

  smooth float depth;
  smooth float depthOffset;
} vs_out[];

out GS_OUT {
  smooth vec2 vertUv;
  flat vec2 vertTpage;
  flat vec2 vertClut;
  flat int vertBpp;
  smooth vec4 vertColour;
  flat int vertFlags;

  flat int translucency;

  flat float widthMultiplier;
  flat int widthMask;
  flat int indexShift;
  flat int indexMask;

  smooth float depth;
  smooth float depthOffset;
} gs_out;

// Shared with the vertex stage (bound once per program by the engine).
layout(std140) uniform transforms {
  mat4 camera;
  mat4 projection;
};

layout(std140) uniform projectionInfo {
  float znear;
  /** PS1 projection plane distance (H) */
  float zfar;
  float zdiffInv;
  /** 0: ortho, 1: PS1 perspective, 2: modern perspective */
  float projectionMode;
};

uniform int usePs1Depth;
uniform vec2 tpageOverride;

void emitDebug(int, float, vec4);

float min3(float a, float b, float c) {
  return min(a, min(b, c));
}

// Recover pre-projection view-space Z from a clip-space position.
// Returns -1 for w <= 0 (behind camera; stock culls those too).
float recoverZ(vec4 clip, mat4 invProj) {
  if (clip.w <= 0.0) {
    return -1.0;
  }
  vec4 ndc = vec4(clip.xyz / clip.w, 1.0);
  vec4 view = invProj * ndc;
  return view.z / view.w;
}

void main() {
  // Keep-alive (same as stock): prevent stripping tpageOverride.
  if (tpageOverride.x > zfar + 9999.0) {
  }

  vec4 debug;
  if (projection[0][0] == 0.0 && projection[1][1] == 0.0 && projection[2][2] == 0.0) {
    // Transforms UBO unreadable in the GS (never call inverse on it).
    debug = vec4(1.0, 0.5, 0.0, 1.0); // ORANGE
  } else {
    mat4 invProj = inverse(projection);
    float z0 = recoverZ(gl_in[0].gl_Position, invProj);
    float z2 = recoverZ(gl_in[2].gl_Position, invProj);
    float z4 = recoverZ(gl_in[4].gl_Position, invProj);
    float zmin = min3(z0, z2, z4);

    if (zfar > 10000.0) {
      debug = vec4(1.0, 0.0, 1.0, 1.0); // MAGENTA
    } else if (z0 == 0.0 && z2 == 0.0 && z4 == 0.0) {
      debug = vec4(1.0, 0.0, 0.0, 1.0); // RED
    } else if (zmin < 1.0) {
      debug = vec4(1.0, 1.0, 0.0, 1.0); // YELLOW
    } else if (zmin <= zfar * 0.5) {
      debug = vec4(0.0, 1.0, 1.0, 1.0); // CYAN
    } else {
      debug = vec4(1.0, 1.0, 1.0, 1.0); // WHITE
    }
  }

  // Depth handling identical to stock (representative depth behavior).
  float avgDepth = gl_in[0].gl_Position.z + gl_in[2].gl_Position.z + gl_in[4].gl_Position.z;
  avgDepth /= 3.0;

  emitDebug(0, avgDepth, debug);
  emitDebug(2, avgDepth, debug);
  emitDebug(4, avgDepth, debug);
  EndPrimitive();
}

void emitDebug(int i, float depth, vec4 debug) {
  gs_out.vertUv = vs_out[i].vertUv;
  gs_out.vertTpage = vs_out[i].vertTpage;
  gs_out.vertClut = vs_out[i].vertClut;
  gs_out.vertBpp = vs_out[i].vertBpp;
  gs_out.vertColour = debug;
  // Force untextured + opaque: tmd.fsh then outputs vertColour flat,
  // with no texture fetch and no translucency discard.
  gs_out.vertFlags = 0;
  gs_out.translucency = vs_out[i].translucency;
  gs_out.widthMultiplier = vs_out[i].widthMultiplier;
  gs_out.widthMask = vs_out[i].widthMask;
  gs_out.indexShift = vs_out[i].indexShift;
  gs_out.indexMask = vs_out[i].indexMask;
  gs_out.depth = vs_out[i].depth;
  gs_out.depthOffset = vs_out[i].depthOffset;

  gl_Position = gl_in[i].gl_Position;

  if(usePs1Depth != 0) {
    gl_Position.z = depth;
  }

  EmitVertex();
}
