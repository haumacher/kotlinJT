package de.haumacher.kotlinjt.shape

import de.haumacher.kotlinjt.JtFormatException

/**
 * The polygon mesh topology decoder (v10 Annex D; reference source in the JT 9.5 reference,
 * Appendix E — "MeshDecoder"/"DualVFMesh"): reconstructs the dual vertex-facet mesh from the
 * Topologically Compressed Rep Data symbol streams. In the dual, a vertex is a primal face
 * (its valence = the primal face's degree, 3 for triangles) and a face is a primal vertex
 * (its degree ring enumerates the incident primal faces); primal vertex numbers equal the
 * dual face creation order, which is the order the unique vertex coordinates are stored in.
 *
 * The decode is strict: any inconsistency in the symbol streams throws [JtFormatException]
 * (the element decode turns that into an opaque carry with a named note). Fixture-verified
 * against all 12 tri-strip bodies of the local 9.5 file.
 */
internal class TopologyDecoder(
    private val faceDegreeSymbols: List<List<Int>>,
    private val vertexValences: List<Int>,
    private val vertexGroups: List<Int>,
    private val vertexFlags: List<Int>,
    private val faceAttributeMasks: List<List<Long>>,
    private val highDegreeMasks: List<Int>,
    private val splitFaceSymbols: List<Int>,
    private val splitFacePositions: List<Int>,
) {
    init {
        require(faceDegreeSymbols.size == 8 && faceAttributeMasks.size == 8)
    }

    // --- the dual VFMesh under construction ---

    private class VtxEnt(val valence: Int, val group: Int, val flags: Int) {
        val faces = IntArray(valence) { -1 }
    }

    private class FaceEnt(val degree: Int, val mask: Long, val firstAttr: Int, val attrCount: Int) {
        val vts = IntArray(degree) { -1 }
        var emptyDegree = degree
    }

    private val vts = ArrayList<VtxEnt>()
    private val faces = ArrayList<FaceEnt>()

    // --- symbol stream positions ---

    private val degPos = IntArray(8)
    private var valPos = 0
    private var grpPos = 0
    private var flagPos = 0
    private val maskPos = IntArray(8)
    private var maskLrgPos = 0
    private var splitFacePos = 0
    private var splitPosPos = 0
    private var attrCounter = 0

    // --- active face queue ---

    private val activeFaces = ArrayList<Int>()
    private val removedFaces = HashSet<Int>()

    fun decode(): DualVFMesh {
        while (initNewComponent()) {
            while (true) {
                val face = nextActiveFace()
                if (face == -1) break
                completeFace(face)
                removedFaces.add(face)
            }
        }
        // Everything read must be consumed and every slot connected — a partial mesh is a
        // decode failure, never a silent result.
        if (valPos != vertexValences.size) {
            throw JtFormatException("topology decode consumed $valPos of ${vertexValences.size} vertex valences")
        }
        for (i in 0 until 8) {
            if (degPos[i] != faceDegreeSymbols[i].size) {
                throw JtFormatException("topology decode left face degree symbols unconsumed in context $i")
            }
            if (maskPos[i] != faceAttributeMasks[i].size) {
                throw JtFormatException("topology decode left face attribute masks unconsumed in context $i")
            }
        }
        if (splitFacePos != splitFaceSymbols.size || splitPosPos != splitFacePositions.size) {
            throw JtFormatException("topology decode left split face symbols unconsumed")
        }
        for ((i, v) in vts.withIndex()) {
            if (v.faces.any { it == -1 }) throw JtFormatException("dual vertex $i is incomplete after topology decode")
        }
        for ((i, f) in faces.withIndex()) {
            if (f.vts.any { it == -1 }) throw JtFormatException("dual face $i is incomplete after topology decode")
        }
        return DualVFMesh(
            vts.map { DualVFMesh.Vertex(it.valence, it.group, it.flags, it.faces.toList()) },
            faces.map { DualVFMesh.Face(it.degree, it.mask, it.firstAttr, it.attrCount, it.vts.toList()) },
            attrCounter,
        )
    }

    // --- symbol readers ---

    private fun nextValence(): Int = if (valPos < vertexValences.size) vertexValences[valPos++] else -1

    private fun nextGroup(): Int = if (grpPos < vertexGroups.size) vertexGroups[grpPos++] else -1

    private fun nextFlag(): Int = if (flagPos < vertexFlags.size) vertexFlags[flagPos++] else 0

    private fun nextDegree(context: Int): Int {
        val stream = faceDegreeSymbols[context]
        return if (degPos[context] < stream.size) stream[degPos[context]++] else -1
    }

    private fun nextMask(context: Int): Long {
        val stream = faceAttributeMasks[context]
        return if (maskPos[context] < stream.size) stream[maskPos[context]++] else 0L
    }

    private fun nextSplitFace(): Int = if (splitFacePos < splitFaceSymbols.size) splitFaceSymbols[splitFacePos++] else -1

    private fun nextSplitPosition(): Int = if (splitPosPos < splitFacePositions.size) splitFacePositions[splitPosPos++] else -1

    /**
     * The compression context of the next face degree symbol: derived from the vertex's
     * valence and the total known degree of its already-decoded faces (Appendix E).
     */
    private fun faceContext(vtx: Int): Int {
        val entry = vts[vtx]
        var knownFaces = 0
        var knownTotalDegree = 0
        for (face in entry.faces) {
            if (face < 0 || face >= faces.size) continue
            knownFaces++
            knownTotalDegree += faces[face].degree
        }
        return when (entry.valence) {
            3 ->
                if (knownTotalDegree < knownFaces * 6) {
                    0
                } else if (knownTotalDegree == knownFaces * 6) {
                    1
                } else {
                    2
                }
            4 ->
                if (knownTotalDegree < knownFaces * 4) {
                    3
                } else if (knownTotalDegree == knownFaces * 4) {
                    4
                } else {
                    5
                }
            5 -> 6
            else -> 7
        }
    }

    // --- decoder machine (MeshCodec of the reference) ---

    private fun initNewComponent(): Boolean {
        val vtx = ioVtx()
        if (vtx == -1) return false
        for (slot in 0 until vts[vtx].valence) {
            activateFace(vtx, slot)
        }
        return true
    }

    private fun completeFace(face: Int) {
        while (true) {
            val slot = faces[face].vts.indexOf(-1)
            if (slot == -1) break
            val vtx = activateVtx(face, slot)
            completeVtx(vtx, slot)
        }
    }

    private fun activateVtx(
        face: Int,
        vtxSlot: Int,
    ): Int {
        val vtx = ioVtx()
        if (vtx == -1) throw JtFormatException("vertex valence stream exhausted mid-mesh")
        vts[vtx].faces[0] = face
        addVtxToFace(vtx, 0, face, vtxSlot)
        return vtx
    }

    private fun activateFace(
        vtx: Int,
        vtxSlot: Int,
    ): Int {
        var face = ioFace(vtx)
        if (face >= 0) {
            vts[vtx].faces[vtxSlot] = face
            setFaceVtx(face, 0, vtx)
            activeFaces.add(face)
        } else {
            // A SPLIT: the face already exists in the active queue.
            face = ioSplitFace()
            val faceSlot = ioSplitPosition()
            if (face < 0 || faceSlot < 0) throw JtFormatException("split face symbols exhausted or invalid")
            vts[vtx].faces[vtxSlot] = face
            addVtxToFace(vtx, vtxSlot, face, faceSlot)
        }
        return face
    }

    private fun completeVtx(
        vtx: Int,
        vtxSlot0: Int,
    ) {
        val valence = vts[vtx].valence
        // Walk counter-clockwise from face slot 0, linking already-reachable faces.
        var vp = vts[vtx].faces[0]
        var jp = vtxSlot0
        var i = 1
        while (i < valence) {
            val vn = vts[vtx].faces[i]
            if (vn == -1) break
            jp = decMod(jp, faces[vp].degree)
            val vtx2 = faces[vp].vts[jp]
            if (vtx2 == -1) break
            var jn = faces[vn].vts.indexOf(vtx2)
            if (jn < 0) throw JtFormatException("topology inconsistency: neighbouring vertex not on shared face")
            jn = decMod(jn, faces[vn].degree)
            addVtxToFace(vtx, i, vn, jn)
            vp = vn
            jp = jn
            i++
        }
        if (i >= valence) return
        val iLast = i
        // Walk clockwise from face slot 0.
        vp = vts[vtx].faces[0]
        jp = vtxSlot0
        i = valence - 1
        while (i >= iLast) {
            val vn = vts[vtx].faces[i]
            if (vn == -1) break
            jp = incMod(jp, faces[vp].degree)
            val vtx2 = faces[vp].vts[jp]
            if (vtx2 == -1) break
            var jn = faces[vn].vts.indexOf(vtx2)
            if (jn < 0) throw JtFormatException("topology inconsistency: neighbouring vertex not on shared face")
            jn = incMod(jn, faces[vn].degree)
            addVtxToFace(vtx, i, vn, jn)
            vp = vn
            jp = jn
            i--
        }
        if (i < iLast) return
        // Activate the remaining faces that cannot be deduced from assembled topology.
        for (slot in iLast..i) {
            activateFace(vtx, slot)
        }
    }

    private fun setFaceVtx(
        face: Int,
        slot: Int,
        vtx: Int,
    ) {
        val entry = faces[face]
        if (entry.vts[slot] != vtx) entry.emptyDegree--
        entry.vts[slot] = vtx
    }

    private fun addVtxToFace(
        vtx: Int,
        faceSlot: Int,
        face: Int,
        vtxSlot: Int,
    ) {
        val degree = faces[face].degree
        val slotCcw = incMod(vtxSlot, degree)
        val slotCw = decMod(vtxSlot, degree)
        setFaceVtx(face, vtxSlot, vtx)
        // Connect across the shared edge clockwise from vtx at face.
        val fp = faces[face].vts[slotCw]
        if (fp != -1) {
            var ip = vts[fp].faces.indexOf(face)
            val vSlotCcw = incMod(faceSlot, vts[vtx].valence)
            if (ip >= 0 && vts[vtx].faces[vSlotCcw] == -1) {
                ip = decMod(ip, vts[fp].valence)
                vts[vtx].faces[vSlotCcw] = vts[fp].faces[ip]
            }
        }
        // Connect across the shared edge counter-clockwise from vtx at face.
        val fn = faces[face].vts[slotCcw]
        if (fn != -1) {
            var iq = vts[fn].faces.indexOf(face)
            val vSlotCw = decMod(faceSlot, vts[vtx].valence)
            if (iq >= 0 && vts[vtx].faces[vSlotCw] == -1) {
                iq = incMod(iq, vts[fn].valence)
                vts[vtx].faces[vSlotCw] = vts[fn].faces[iq]
            }
        }
    }

    /**
     * Picks the next active face: among the most recent 16 queue entries, the one with the
     * fewest incomplete degree-ring slots (the reference's exact heuristic — the decoder must
     * mirror the encoder's choice).
     */
    private fun nextActiveFace(): Int {
        while (activeFaces.isNotEmpty() && activeFaces.last() in removedFaces) {
            activeFaces.removeAt(activeFaces.size - 1)
        }
        var best = -1
        var bestEmpty = Int.MAX_VALUE
        var i = activeFaces.size - 1
        var seen = 0
        while (i >= 0 && seen < 16) {
            val face = activeFaces[i]
            if (face in removedFaces) {
                activeFaces.removeAt(i)
                i--
                continue
            }
            val empty = faces[face].emptyDegree
            if (empty < bestEmpty) {
                bestEmpty = empty
                best = face
            }
            seen++
            i--
        }
        return best
    }

    // --- polymorphic I/O of the reference decoder ---

    private fun ioVtx(): Int {
        val valence = nextValence()
        if (valence <= -1) return -1
        if (valence <= 0) throw JtFormatException("dual vertex valence $valence is not positive")
        vts.add(VtxEnt(valence, nextGroup(), nextFlag()))
        return vts.size - 1
    }

    private fun ioFace(vtx: Int): Int {
        val context = faceContext(vtx)
        val degree = nextDegree(context)
        if (degree == 0) return -1 // SPLIT marker
        if (degree < 0) throw JtFormatException("face degree stream exhausted in context $context")
        val mask: Long
        if (degree <= 64) {
            val maskContext =
                if (degree - 2 < 0) {
                    0
                } else if (degree - 2 > 7) {
                    7
                } else {
                    degree - 2
                }
            mask = nextMask(maskContext)
        } else {
            // High-degree masks: adjoined end-to-end in the raw word array.
            val words = (degree + 31) / 32
            if (maskLrgPos + words > highDegreeMasks.size) {
                throw JtFormatException("high-degree face attribute masks exhausted")
            }
            // Only the first 64 bits participate in the Long mask; attribute counting walks the words.
            var attrCount = 0
            var m = 0L
            for (wordIndex in 0 until words) {
                val word = highDegreeMasks[maskLrgPos + wordIndex]
                attrCount += countBits(word)
                if (wordIndex == 0) m = word.toLong() and 0xFFFFFFFFL
                if (wordIndex == 1) m = m or ((word.toLong() and 0xFFFFFFFFL) shl 32)
            }
            maskLrgPos += words
            faces.add(FaceEnt(degree, m, attrCounter, attrCount))
            attrCounter += attrCount
            return faces.size - 1
        }
        val attrCount = countBits64(mask)
        if (attrCount > degree) throw JtFormatException("face attribute mask has $attrCount bits for degree $degree")
        faces.add(FaceEnt(degree, mask, attrCounter, attrCount))
        attrCounter += attrCount
        return faces.size - 1
    }

    private fun ioSplitFace(): Int {
        val offset = nextSplitFace()
        if (offset <= 0 || offset > activeFaces.size) return -1
        return activeFaces[activeFaces.size - offset]
    }

    private fun ioSplitPosition(): Int = nextSplitPosition()

    private fun incMod(
        value: Int,
        modulus: Int,
    ): Int = if (value + 1 >= modulus) 0 else value + 1

    private fun decMod(
        value: Int,
        modulus: Int,
    ): Int = if (value - 1 < 0) modulus - 1 else value - 1

    private fun countBits(word: Int): Int {
        var x = word
        var count = 0
        while (x != 0) {
            count += x and 1
            x = x ushr 1
        }
        return count
    }

    private fun countBits64(mask: Long): Int {
        var x = mask
        var count = 0
        while (x != 0L) {
            count += (x and 1L).toInt()
            x = x ushr 1
        }
        return count
    }
}

/**
 * The reconstructed dual vertex-facet mesh. Dual vertices are primal faces (triangles for a
 * tri-strip set; cover faces carry flag bit 0); dual faces are primal vertices, numbered in
 * the order their coordinates are stored.
 */
internal class DualVFMesh(
    val vertices: List<Vertex>,
    val faces: List<Face>,
    /** Total number of attribute records assigned (== 1-bits across all attribute masks). */
    val attributeCount: Int,
) {
    class Vertex(
        val valence: Int,
        val group: Int,
        val flags: Int,
        /** The incident dual faces (= primal vertex numbers), counter-clockwise. */
        val faces: List<Int>,
    )

    class Face(
        val degree: Int,
        /** The degree-ring attribute mask (bit per slot; only meaningful up to 64 bits). */
        val mask: Long,
        val firstAttr: Int,
        val attrCount: Int,
        /** The incident dual vertices (= primal face numbers), counter-clockwise. */
        val vts: List<Int>,
    )

    /**
     * The attribute record referenced by dual face [face] at ring slot [slot]: a 1-bit starts
     * a new record, a 0-bit reuses the record of the slot clockwise of it (wrapping).
     */
    fun attributeAt(
        face: Int,
        slot: Int,
    ): Int {
        val entry = faces[face]
        if (entry.attrCount == 0) return -1
        var k = slot
        var guard = entry.degree
        while (guard-- > 0 && (entry.mask ushr k) and 1L == 0L) {
            k = if (k - 1 < 0) entry.degree - 1 else k - 1
        }
        var attrSlot = 0
        for (bit in 0 until k) {
            if ((entry.mask ushr bit) and 1L == 1L) attrSlot++
        }
        return entry.firstAttr + attrSlot
    }
}
