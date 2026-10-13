package android.net

import android.os.Parcel

class FakeUri(private val uriString: String = "content://com.example.test/document/1") : Uri() {
    override fun isHierarchical() = false
    override fun isOpaque() = false
    override fun isRelative() = false
    override fun isAbsolute() = true
    override fun getScheme() = "content"
    override fun getSchemeSpecificPart() = "//com.example.test/document/1"
    override fun getEncodedSchemeSpecificPart() = "//com.example.test/document/1"
    override fun getAuthority() = "com.example.test"
    override fun getEncodedAuthority() = "com.example.test"
    override fun getFragment() = null
    override fun getEncodedFragment() = null
    override fun getPath() = "/document/1"
    override fun getEncodedPath() = "/document/1"
    override fun getQuery() = null
    override fun getEncodedQuery() = null
    override fun getEncodedUserInfo() = null
    override fun getHost() = "com.example.test"
    override fun getPort() = -1
    override fun getUserInfo() = null
    override fun getPathSegments() = listOf("document", "1")
    override fun getLastPathSegment() = "1"
    override fun toString() = uriString
    override fun buildUpon() = error("")
    override fun writeToParcel(out: Parcel, flags: Int) {}
    override fun describeContents() = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Uri) return false
        return this.toString() == other.toString()
    }

    override fun hashCode(): Int {
        return uriString.hashCode()
    }
}
