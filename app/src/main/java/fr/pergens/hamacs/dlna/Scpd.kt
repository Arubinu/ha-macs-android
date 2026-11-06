package fr.pergens.hamacs.dlna

/**
 * Descriptions des services UPnP (SCPD) du lecteur : actions, arguments et variables d'état liées. Chaque argument
 * désigne une variable déclarée (Home Assistant / async_upnp_client refuse un service incohérent).
 */
internal object Scpd {
    private class V(
        val name: String, val type: String, val events: Boolean = false,
        val allowed: List<String>? = null, val range: IntRange? = null,
    )

    /** Argument : nom, sortie (out) ou entrée, variable liée. */
    private class A(val name: String, val out: Boolean, val v: String)
    private class Act(val name: String, vararg val args: A)

    private fun i(name: String, v: String) = A(name, false, v)
    private fun o(name: String, v: String) = A(name, true, v)
    private val ID = i("InstanceID", "A_ARG_TYPE_InstanceID")

    private val avTransport = build(
        listOf(
            Act("SetAVTransportURI", ID, i("CurrentURI", "AVTransportURI"), i("CurrentURIMetaData", "AVTransportURIMetaData")),
            Act(
                "GetMediaInfo", ID, o("NrTracks", "NumberOfTracks"), o("MediaDuration", "CurrentMediaDuration"),
                o("CurrentURI", "AVTransportURI"), o("CurrentURIMetaData", "AVTransportURIMetaData"),
                o("NextURI", "NextAVTransportURI"), o("NextURIMetaData", "NextAVTransportURIMetaData"),
                o("PlayMedium", "PlaybackStorageMedium"), o("RecordMedium", "RecordStorageMedium"),
                o("WriteStatus", "RecordMediumWriteStatus"),
            ),
            Act(
                "GetTransportInfo", ID, o("CurrentTransportState", "TransportState"),
                o("CurrentTransportStatus", "TransportStatus"), o("CurrentSpeed", "TransportPlaySpeed"),
            ),
            Act(
                "GetPositionInfo", ID, o("Track", "CurrentTrack"), o("TrackDuration", "CurrentTrackDuration"),
                o("TrackMetaData", "CurrentTrackMetaData"), o("TrackURI", "CurrentTrackURI"),
                o("RelTime", "RelativeTimePosition"), o("AbsTime", "AbsoluteTimePosition"),
                o("RelCount", "RelativeCounterPosition"), o("AbsCount", "AbsoluteCounterPosition"),
            ),
            Act(
                "GetDeviceCapabilities", ID, o("PlayMedia", "PossiblePlaybackStorageMedia"),
                o("RecMedia", "PossibleRecordStorageMedia"), o("RecQualityModes", "PossibleRecordQualityModes"),
            ),
            Act("GetTransportSettings", ID, o("PlayMode", "CurrentPlayMode"), o("RecQualityMode", "CurrentRecordQualityMode")),
            Act("Stop", ID),
            Act("Play", ID, i("Speed", "TransportPlaySpeed")),
            Act("Pause", ID),
            Act("Seek", ID, i("Unit", "A_ARG_TYPE_SeekMode"), i("Target", "A_ARG_TYPE_SeekTarget")),
            Act("GetCurrentTransportActions", ID, o("Actions", "CurrentTransportActions")),
        ),
        listOf(
            V("TransportState", "string", allowed = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT")),
            V("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
            V("PlaybackStorageMedium", "string", allowed = listOf("NONE", "NETWORK")),
            V("RecordStorageMedium", "string", allowed = listOf("NOT_IMPLEMENTED")),
            V("PossiblePlaybackStorageMedia", "string"),
            V("PossibleRecordStorageMedia", "string"),
            V("CurrentPlayMode", "string", allowed = listOf("NORMAL")),
            V("TransportPlaySpeed", "string", allowed = listOf("1")),
            V("RecordMediumWriteStatus", "string", allowed = listOf("NOT_IMPLEMENTED")),
            V("CurrentRecordQualityMode", "string", allowed = listOf("NOT_IMPLEMENTED")),
            V("PossibleRecordQualityModes", "string"),
            V("NumberOfTracks", "ui4", range = 0..1),
            V("CurrentTrack", "ui4", range = 0..1),
            V("CurrentTrackDuration", "string"),
            V("CurrentMediaDuration", "string"),
            V("CurrentTrackMetaData", "string"),
            V("CurrentTrackURI", "string"),
            V("AVTransportURI", "string"),
            V("AVTransportURIMetaData", "string"),
            V("NextAVTransportURI", "string"),
            V("NextAVTransportURIMetaData", "string"),
            V("RelativeTimePosition", "string"),
            V("AbsoluteTimePosition", "string"),
            V("RelativeCounterPosition", "i4"),
            V("AbsoluteCounterPosition", "i4"),
            V("CurrentTransportActions", "string"),
            V("LastChange", "string", events = true),
            V("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME")),
            V("A_ARG_TYPE_SeekTarget", "string"),
            V("A_ARG_TYPE_InstanceID", "ui4"),
        ),
    )

    private val renderingControl = build(
        listOf(
            Act("ListPresets", ID, o("CurrentPresetNameList", "PresetNameList")),
            Act("SelectPreset", ID, i("PresetName", "A_ARG_TYPE_PresetName")),
            Act("GetMute", ID, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentMute", "Mute")),
            Act("SetMute", ID, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredMute", "Mute")),
            Act("GetVolume", ID, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentVolume", "Volume")),
            Act("SetVolume", ID, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredVolume", "Volume")),
        ),
        listOf(
            V("PresetNameList", "string"),
            V("Mute", "boolean"),
            V("Volume", "ui2", range = 0..100),
            V("LastChange", "string", events = true),
            V("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
            V("A_ARG_TYPE_InstanceID", "ui4"),
            V("A_ARG_TYPE_PresetName", "string", allowed = listOf("FactoryDefaults")),
        ),
    )

    private val connectionManager = build(
        listOf(
            Act("GetProtocolInfo", o("Source", "SourceProtocolInfo"), o("Sink", "SinkProtocolInfo")),
            Act("GetCurrentConnectionIDs", o("ConnectionIDs", "CurrentConnectionIDs")),
            Act(
                "GetCurrentConnectionInfo", i("ConnectionID", "A_ARG_TYPE_ConnectionID"),
                o("RcsID", "A_ARG_TYPE_RcsID"), o("AVTransportID", "A_ARG_TYPE_AVTransportID"),
                o("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), o("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
                o("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), o("Direction", "A_ARG_TYPE_Direction"),
                o("Status", "A_ARG_TYPE_ConnectionStatus"),
            ),
        ),
        listOf(
            V("SourceProtocolInfo", "string", events = true),
            V("SinkProtocolInfo", "string", events = true),
            V("CurrentConnectionIDs", "string", events = true),
            V("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
            V("A_ARG_TYPE_ConnectionManager", "string"),
            V("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
            V("A_ARG_TYPE_ProtocolInfo", "string"),
            V("A_ARG_TYPE_ConnectionID", "i4"),
            V("A_ARG_TYPE_AVTransportID", "i4"),
            V("A_ARG_TYPE_RcsID", "i4"),
        ),
    )

    fun of(service: String): String? = when (service) {
        "AVTransport" -> avTransport
        "RenderingControl" -> renderingControl
        "ConnectionManager" -> connectionManager
        else -> null
    }

    private fun build(actions: List<Act>, vars: List<V>): String {
        val names = vars.map { it.name }.toSet()
        actions.forEach { a -> a.args.forEach { require(it.v in names) { "${a.name}.${it.name} → ${it.v}" } } }
        val acts = actions.joinToString("") { a ->
            val args = a.args.joinToString("") {
                "<argument><name>${it.name}</name><direction>${if (it.out) "out" else "in"}</direction><relatedStateVariable>${it.v}</relatedStateVariable></argument>"
            }
            "<action><name>${a.name}</name>" + (if (args.isEmpty()) "" else "<argumentList>$args</argumentList>") + "</action>"
        }
        val vs = vars.joinToString("") { v ->
            val extra = (v.allowed?.let { l -> "<allowedValueList>" + l.joinToString("") { "<allowedValue>$it</allowedValue>" } + "</allowedValueList>" } ?: "") +
                (v.range?.let { "<allowedValueRange><minimum>${it.first}</minimum><maximum>${it.last}</maximum><step>1</step></allowedValueRange>" } ?: "")
            "<stateVariable sendEvents=\"${if (v.events) "yes" else "no"}\"><name>${v.name}</name><dataType>${v.type}</dataType>$extra</stateVariable>"
        }
        return """<?xml version="1.0" encoding="utf-8"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>$acts</actionList><serviceStateTable>$vs</serviceStateTable></scpd>"""
    }
}
