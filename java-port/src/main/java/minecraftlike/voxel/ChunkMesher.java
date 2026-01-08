
package minecraftlike.voxel;

public final class ChunkMesher {
    // Face convention: 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
    // Vertex format: pos(3), uv(2), normal(3), sky(1), emissive(1)

    public static ChunkMesh buildMesh(World world, int cx, int cz, BlockTextures textures) {
        int baseX = cx * Chunk.SIZE;
        int baseZ = cz * Chunk.SIZE;

        // Heightmap: highest solid block per (local x,z) in this chunk.
        // Used as a cheap "is sky visible above this air cell" test.
        int[] topSolidY = new int[Chunk.SIZE * Chunk.SIZE];
        for (int lz = 0; lz < Chunk.SIZE; lz++) {
            for (int lx = 0; lx < Chunk.SIZE; lx++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int top = -1;
                for (int y = Chunk.HEIGHT - 1; y >= 0; y--) {
                    // ...existing code...
                        top = y;
                        break;
                    }
                }
                topSolidY[lz * Chunk.SIZE + lx] = top;
            }
        }

        FloatList verts = new FloatList(Chunk.SIZE * Chunk.SIZE * 6 * 6 * 10);
        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                for (int y = 0; y < Chunk.HEIGHT; y++) {
                    BlockType t = BlockType.DIRT;
                    if (t == BlockType.AIR) continue;
                    for (int face = 0; face < 6; face++) {
                        float sky = (face == 3) ? 0.0f : skyFactor(world, topSolidY, baseX, baseZ, wx, y, wz);
                        addFace(verts, textures.uv(t, face), wx, y, wz, face, sky);
                    }
                }
            }
        }
        float[] arr = verts.toArray();
        int vertexCount = arr.length / 10;
        return new ChunkMesh(arr, vertexCount);
     }

    private static boolean occludes(BlockType t) {
        // Partial blocks should not fully occlude neighbor faces.
        return t != BlockType.AIR && t != BlockType.TALL_GRASS && t != BlockType.TORCH;
    }

    private static void addFace(FloatList out, UvRect uv, int wx, int y, int wz, int face, float sky) {
        float x0 = wx;
        float x1 = wx + 1;
        float y0 = y;
        float y1 = y + 1;
        float z0 = wz;
        float z1 = wz + 1;

        float nx = 0f, ny = 0f, nz = 0f;
        switch (face) {
            case 0 -> { nx = 1f; ny = 0f; nz = 0f; }
            case 1 -> { nx = -1f; ny = 0f; nz = 0f; }
            case 2 -> { nx = 0f; ny = 1f; nz = 0f; }
            case 3 -> { nx = 0f; ny = -1f; nz = 0f; }
            case 4 -> { nx = 0f; ny = 0f; nz = 1f; }
            case 5 -> { nx = 0f; ny = 0f; nz = -1f; }
        }

        // UV mapping (note: v axis depends on atlas build; works with our shader and flip-on-load).
        // Für Wasser-Oberseite: UVs immer (0,0)-(1,1), alle anderen Faces wie bisher
        float u0 = uv.u0();
        float v0 = uv.v0();
        float u1 = uv.u1();
        float v1 = uv.v1();

        // Two triangles per face.
        // Vertices are CCW when viewed from outside so GL_BACK culling works.
        // UVs are assigned as (bottom-left, bottom-right, top-right, top-left)
        // to keep side textures upright (no rotation/skew artifacts).
        switch (face) {
            case 0 -> {
                // +X (right points -Z, up +Y)
                addQuad(out,
                    x1, y0, z1,  // bl
                    x1, y0, z0,  // br
                    x1, y1, z0,  // tr
                    x1, y1, z1,  // tl
                    uv, nx, ny, nz, sky
                );
            }
            case 1 -> {
                // -X (right +Z, up +Y)
                addQuad(out,
                    x0, y0, z0,
                    x0, y0, z1,
                    x0, y1, z1,
                    x0, y1, z0,
                    uv, nx, ny, nz, sky
                );
            }
            case 2 -> {
                // +Y (top): use right +X, up -Z
                addQuad(out,
                    x0, y1, z1,
                    x1, y1, z1,
                    x1, y1, z0,
                    x0, y1, z0,
                    uv, nx, ny, nz, sky
                );
            }
            case 3 -> {
                // -Y (bottom): use right +X, up +Z
                addQuad(out,
                    x0, y0, z0,
                    x1, y0, z0,
                    x1, y0, z1,
                    x0, y0, z1,
                    uv, nx, ny, nz, sky
                );
            }
            case 4 -> {
                // +Z (right +X, up +Y)
                addQuad(out,
                    x0, y0, z1,
                    x1, y0, z1,
                    x1, y1, z1,
                    x0, y1, z1,
                    uv, nx, ny, nz, sky
                );
            }
            case 5 -> {
                // -Z (right -X, up +Y)
                addQuad(out,
                    x1, y0, z0,
                    x0, y0, z0,
                    x0, y1, z0,
                    x1, y1, z0,
                    uv, nx, ny, nz, sky
                );
            }
        }
    }

    private static void addQuad(
        FloatList out,
        float bx, float by, float bz,  // bottom-left
        float rx, float ry, float rz,  // bottom-right
        float tx, float ty, float tz,  // top-right
        float lx, float ly, float lz,  // top-left
        UvRect uv,
        float nx, float ny, float nz,
        float sky
    ) {
        float u0 = Math.max(0.0f, Math.min(1.0f, uv.u0()));
        float v0 = Math.max(0.0f, Math.min(1.0f, uv.v0()));
        float u1 = Math.max(0.0f, Math.min(1.0f, uv.u1()));
        float v1 = Math.max(0.0f, Math.min(1.0f, uv.v1()));

        // Debug: Zeige die ersten 10 Faces pro Chunk
        if (out.size() < 100) {
            System.out.println("addQuad: bl(" + bx + "," + by + "," + bz + ") br(" + rx + "," + ry + "," + rz + ") tr(" + tx + "," + ty + "," + tz + ") tl(" + lx + "," + ly + "," + lz + ")");
        }

        // (bl, br, tr) (bl, tr, tl)
        v(out, bx, by, bz, u0, v0, nx, ny, nz, sky);
        v(out, rx, ry, rz, u1, v0, nx, ny, nz, sky);
        v(out, tx, ty, tz, u1, v1, nx, ny, nz, sky);

        v(out, bx, by, bz, u0, v0, nx, ny, nz, sky);
        v(out, tx, ty, tz, u1, v1, nx, ny, nz, sky);
        v(out, lx, ly, lz, u0, v1, nx, ny, nz, sky);
    }

    private static void v(FloatList out, float x, float y, float z, float u, float v, float nx, float ny, float nz, float sky) {
        v(out, x, y, z, u, v, nx, ny, nz, sky, 0.0f);
    }

    private static void v(FloatList out, float x, float y, float z, float u, float v, float nx, float ny, float nz, float sky, float emissive) {
        // pos3
        out.add(x);
        out.add(y);
        out.add(z);
        // uv2
        out.add(u);
        out.add(v);
        // normal3
        out.add(nx);
        out.add(ny);
        out.add(nz);

        // sky1
        out.add(sky);

        // emissive1
        out.add(emissive);
    }

    private static void addCross(FloatList out, UvRect uv, int wx, int y, int wz, float sky, float emissive) {
        float x0 = wx;
        float x1 = wx + 1;
        float y0 = y;
        // Make the "top" vertices non-integer Y so voxel.vert's fract(pos.y) produces a usable bend factor.
        // If y1 is exactly y+1, fract(y1) becomes 0 and the wind sway becomes nearly invisible.
        float y1 = y + 0.98f;
        float z0 = wz;
        float z1 = wz + 1;

        float u0 = uv.u0();
        float v0 = uv.v0();
        float u1 = uv.u1();
        float v1 = uv.v1();

        // Use upward normal so it doesn't look too dark from side-facing normals.
        float nx = 0f, ny = 1f, nz = 0f;

        // Plane 1: (x0,z0) -> (x1,z1)
        v(out, x0, y0, z0, u0, v0, nx, ny, nz, sky, emissive);
        v(out, x1, y0, z1, u1, v0, nx, ny, nz, sky, emissive);
        v(out, x1, y1, z1, u1, v1, nx, ny, nz, sky, emissive);

        v(out, x0, y0, z0, u0, v0, nx, ny, nz, sky, emissive);
        v(out, x1, y1, z1, u1, v1, nx, ny, nz, sky, emissive);
        v(out, x0, y1, z0, u0, v1, nx, ny, nz, sky, emissive);

        // Backface for plane 1 (so it renders from both sides with backface culling enabled)
        v(out, x0, y0, z0, u0, v0, nx, ny, nz, sky, emissive);
        v(out, x1, y1, z1, u1, v1, nx, ny, nz, sky, emissive);
        v(out, x1, y0, z1, u1, v0, nx, ny, nz, sky, emissive);

        v(out, x0, y0, z0, u0, v0, nx, ny, nz, sky, emissive);
        v(out, x0, y1, z0, u0, v1, nx, ny, nz, sky, emissive);
        v(out, x1, y1, z1, u1, v1, nx, ny, nz, sky, emissive);

        // Plane 2: (x0,z1) -> (x1,z0)
        v(out, x0, y0, z1, u0, v0, nx, ny, nz, sky, emissive);
        v(out, x1, y0, z0, u1, v0, nx, ny, nz, sky, emissive);
        v(out, x1, y1, z0, u1, v1, nx, ny, nz, sky, emissive);

        v(out, x0, y0, z1, u0, v0, nx, ny, nz, sky, emissive);
        v(out, x1, y1, z0, u1, v1, nx, ny, nz, sky, emissive);
        v(out, x0, y1, z1, u0, v1, nx, ny, nz, sky, emissive);

        // Backface for plane 2
        v(out, x0, y0, z1, u0, v0, nx, ny, nz, sky, emissive);
        v(out, x1, y1, z0, u1, v1, nx, ny, nz, sky, emissive);
        v(out, x1, y0, z0, u1, v0, nx, ny, nz, sky, emissive);

        v(out, x0, y0, z1, u0, v0, nx, ny, nz, sky, emissive);
        v(out, x0, y1, z1, u0, v1, nx, ny, nz, sky, emissive);
        v(out, x1, y1, z0, u1, v1, nx, ny, nz, sky, emissive);
    }

    private static void addOakStairs(FloatList out, BlockTextures textures, int wx, int y, int wz, float sky) {
        // Fixed orientation (for now): the "higher" step is on the +Z half.
        // Shape is a 2-step stair: 0..0.5 over full depth, and 0.5..1.0 over back half.
        final float emissive = 0.0f;

        float x0 = wx;
        float x1 = wx + 1.0f;
        float y0 = y;
        float yMid = y + 0.5f;
        float y1 = y + 1.0f;
        float z0 = wz;
        float zMid = wz + 0.5f;
        float z1 = wz + 1.0f;

        // Use planks UV for all faces.
        UvRect uv = textures.uv(BlockType.PLANKS, 0);
        float u0 = uv.u0();
        float v0 = uv.v0();
        float u1 = uv.u1();
        float v1 = uv.v1();

        // Bottom (-Y)
        addQuadCustom(out,
            x0, y0, z0,
            x1, y0, z0,
            x1, y0, z1,
            x0, y0, z1,
            u0, v0, u1, v1,
            0f, -1f, 0f,
            0.0f,
            emissive
        );

        // Top front half (+Y)
        addQuadCustom(out,
            x0, yMid, zMid,
            x1, yMid, zMid,
            x1, yMid, z0,
            x0, yMid, z0,
            u0, v0, u1, v1,
            0f, 1f, 0f,
            sky,
            emissive
        );

        // Top back half (+Y)
        addQuadCustom(out,
            x0, y1, z1,
            x1, y1, z1,
            x1, y1, zMid,
            x0, y1, zMid,
            u0, v0, u1, v1,
            0f, 1f, 0f,
            sky,
            emissive
        );

        // Front face (-Z), lower step
        // Face 5 convention (-Z): (bl=x1,z0) (br=x0,z0) (tr=x0,...) (tl=x1,...)
        addQuadCustom(out,
            x1, y0, z0,
            x0, y0, z0,
            x0, yMid, z0,
            x1, yMid, z0,
            u0, v0, u1, v1,
            0f, 0f, -1f,
            sky,
            emissive
        );

        // Step riser (-Z) at zMid
        addQuadCustom(out,
            x1, yMid, zMid,
            x0, yMid, zMid,
            x0, y1, zMid,
            x1, y1, zMid,
            u0, v0, u1, v1,
            0f, 0f, -1f,
            sky,
            emissive
        );

        // Back face (+Z), full height
        // Face 4 convention (+Z)
        addQuadCustom(out,
            x0, y0, z1,
            x1, y0, z1,
            x1, y1, z1,
            x0, y1, z1,
            u0, v0, u1, v1,
            0f, 0f, 1f,
            sky,
            emissive
        );

        // Right (+X): lower full depth
        addQuadCustom(out,
            x1, y0, z1,
            x1, y0, z0,
            x1, yMid, z0,
            x1, yMid, z1,
            u0, v0, u1, v1,
            1f, 0f, 0f,
            sky,
            emissive
        );

        // Right (+X): upper back half
        addQuadCustom(out,
            x1, yMid, z1,
            x1, yMid, zMid,
            x1, y1, zMid,
            x1, y1, z1,
            u0, v0, u1, v1,
            1f, 0f, 0f,
            sky,
            emissive
        );

        // Left (-X): lower full depth
        addQuadCustom(out,
            x0, y0, z0,
            x0, y0, z1,
            x0, yMid, z1,
            x0, yMid, z0,
            u0, v0, u1, v1,
            -1f, 0f, 0f,
            sky,
            emissive
        );

        // Left (-X): upper back half
        addQuadCustom(out,
            x0, yMid, zMid,
            x0, yMid, z1,
            x0, y1, z1,
            x0, y1, zMid,
            u0, v0, u1, v1,
            -1f, 0f, 0f,
            sky,
            emissive
        );
    }

    private static void addTorch(FloatList out, BlockTextures textures, int wx, int y, int wz, float sky) {
        // Minecraft-ish torch: a thin vertical rod with emissive.
        final float emissive = 0.55f;

        float cx = wx + 0.5f;
        float cz = wz + 0.5f;
        float half = 0.06f;
        float x0 = cx - half;
        float x1 = cx + half;
        float z0 = cz - half;
        float z1 = cz + half;
        float y0 = y;
        float y1 = y + 0.62f;

        UvRect uvSide = textures.uv(BlockType.TORCH, 0);
        UvRect uvTop = textures.uv(BlockType.TORCH, 2);
        UvRect uvBottom = textures.uv(BlockType.TORCH, 3);

        // +X
        addQuadCustom(out,
            x1, y0, z1,
            x1, y0, z0,
            x1, y1, z0,
            x1, y1, z1,
            uvSide.u0(), uvSide.v0(), uvSide.u1(), uvSide.v1(),
            1f, 0f, 0f,
            sky,
            emissive
        );
        // -X
        addQuadCustom(out,
            x0, y0, z0,
            x0, y0, z1,
            x0, y1, z1,
            x0, y1, z0,
            uvSide.u0(), uvSide.v0(), uvSide.u1(), uvSide.v1(),
            -1f, 0f, 0f,
            sky,
            emissive
        );
        // +Z
        addQuadCustom(out,
            x0, y0, z1,
            x1, y0, z1,
            x1, y1, z1,
            x0, y1, z1,
            uvSide.u0(), uvSide.v0(), uvSide.u1(), uvSide.v1(),
            0f, 0f, 1f,
            sky,
            emissive
        );
        // -Z
        addQuadCustom(out,
            x1, y0, z0,
            x0, y0, z0,
            x0, y1, z0,
            x1, y1, z0,
            uvSide.u0(), uvSide.v0(), uvSide.u1(), uvSide.v1(),
            0f, 0f, -1f,
            sky,
            emissive
        );
        // Top
        addQuadCustom(out,
            x0, y1, z1,
            x1, y1, z1,
            x1, y1, z0,
            x0, y1, z0,
            uvTop.u0(), uvTop.v0(), uvTop.u1(), uvTop.v1(),
            0f, 1f, 0f,
            sky,
            emissive
        );

        // Bottom (rarely visible, but matches Minecraft-style texture set)
        addQuadCustom(out,
            x0, y0, z0,
            x1, y0, z0,
            x1, y0, z1,
            x0, y0, z1,
            uvBottom.u0(), uvBottom.v0(), uvBottom.u1(), uvBottom.v1(),
            0f, -1f, 0f,
            0.0f,
            emissive
        );
    }

    private static void addQuadCustom(
        FloatList out,
        float bx, float by, float bz,
        float rx, float ry, float rz,
        float tx, float ty, float tz,
        float lx, float ly, float lz,
        float u0, float v0, float u1, float v1,
        float nx, float ny, float nz,
        float sky,
        float emissive
    ) {
        v(out, bx, by, bz, u0, v0, nx, ny, nz, sky, emissive);
        v(out, rx, ry, rz, u1, v0, nx, ny, nz, sky, emissive);
        v(out, tx, ty, tz, u1, v1, nx, ny, nz, sky, emissive);

        v(out, bx, by, bz, u0, v0, nx, ny, nz, sky, emissive);
        v(out, tx, ty, tz, u1, v1, nx, ny, nz, sky, emissive);
        v(out, lx, ly, lz, u0, v1, nx, ny, nz, sky, emissive);
    }

    private static float skyFactor(World world, int[] topSolidY, int baseX, int baseZ, int wx, int wy, int wz) {
        // If the adjacent air cell (wx,wy,wz) has no solid blocks above it, treat it as receiving skylight.
        // Fast-path for columns within this chunk (heightmap lookup); fallback to scan for out-of-chunk.
        int lx = wx - baseX;
        int lz = wz - baseZ;
        if (lx >= 0 && lx < Chunk.SIZE && lz >= 0 && lz < Chunk.SIZE) {
            int top = topSolidY[lz * Chunk.SIZE + lx];
            return (top <= wy) ? 1.0f : 0.0f;
        }

        // Outside chunk: scan from top down until we either find a solid above wy or confirm clear.
        for (int y = Chunk.HEIGHT - 1; y > wy; y--) {
            // ...existing code...
        }
        return 1.0f;
    }
}
