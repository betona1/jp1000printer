#!/usr/bin/env python3
"""
Patch Android binary XML: config_webview_packages.xml
Adds com.android.chrome as a WebView provider alongside com.android.webview.
"""
import struct
import sys

def encode_utf8_string(s):
    """Encode string in Android binary XML UTF-8 format: charlen + bytelen + data + null"""
    data = s.encode('utf-8')
    char_len = len(s)
    byte_len = len(data)
    # For lengths < 128, single byte each
    return bytes([char_len, byte_len]) + data + b'\x00'

def pad4(data):
    """Pad to 4-byte boundary"""
    r = len(data) % 4
    if r:
        data += b'\x00' * (4 - r)
    return data

def build_string_pool(strings):
    """Build a complete ResStringPool chunk"""
    # Header
    POOL_TYPE = 0x0001
    HEADER_SIZE = 28
    FLAGS = 0x00000100  # UTF-8

    # Encode all strings
    encoded = []
    for s in strings:
        encoded.append(encode_utf8_string(s))

    # Calculate offsets
    offsets = []
    pos = 0
    for e in encoded:
        offsets.append(pos)
        pos += len(e)

    string_data = b''.join(encoded)
    string_data = pad4(string_data)

    # strings_start = offset from chunk start to string data
    # = HEADER_SIZE + (len(strings) * 4)
    strings_start = HEADER_SIZE + len(strings) * 4 - HEADER_SIZE
    # Actually: strings_start is relative to the chunk header
    # In the chunk: header(28) + offsets(n*4) + string_data
    # strings_start should point to where string data begins relative to chunk start
    # No wait, looking at AOSP: it's the byte offset from the header where string data starts
    # So it's: HEADER_SIZE + n*4 (offset array)
    strings_start = HEADER_SIZE + len(strings) * 4

    # Build offset array
    offset_data = b''
    for off in offsets:
        offset_data += struct.pack('<I', off)

    chunk_data = offset_data + string_data
    chunk_size = HEADER_SIZE + len(chunk_data)

    header = struct.pack('<HHI III I I',
        POOL_TYPE,      # type
        HEADER_SIZE,    # headerSize
        chunk_size,     # size
        len(strings),   # stringCount
        0,              # styleCount
        FLAGS,          # flags (UTF-8)
        strings_start,  # stringsStart
        0               # stylesStart
    )

    return header + chunk_data

def build_start_element(line_num, name_idx, attrs):
    """Build a ResXMLTree_attrExt (START_ELEMENT) chunk.
    attrs: list of (ns, name_idx, raw_value_idx, data_type, data_value)
    """
    ELEM_TYPE = 0x0102
    HEADER_SIZE = 16

    # Ext data
    ns = -1
    attr_start = 20  # bytes from ext start to first attr
    attr_size = 20   # bytes per attribute

    ext = struct.pack('<i i HHH HHH',
        ns,          # namespace
        name_idx,    # name
        attr_start,  # attributeStart
        attr_size,   # attributeSize
        len(attrs),  # attributeCount
        0, 0, 0      # idIndex, classIndex, styleIndex
    )

    attr_data = b''
    for a_ns, a_name, a_raw, a_type, a_data in attrs:
        attr_data += struct.pack('<i i i HBB I',
            a_ns,    # namespace (-1 = none)
            a_name,  # name string index
            a_raw,   # raw value string index (-1 = none)
            8,       # typedValue.size
            0,       # typedValue.res0
            a_type,  # typedValue.dataType
            a_data   # typedValue.data (unsigned for bool 0xFFFFFFFF)
        )

    chunk_size = HEADER_SIZE + len(ext) + len(attr_data)

    header = struct.pack('<HHI I i',
        ELEM_TYPE,
        HEADER_SIZE,
        chunk_size,
        line_num,
        -1  # comment
    )

    return header + ext + attr_data

def build_end_element(line_num, name_idx):
    """Build END_ELEMENT chunk"""
    END_TYPE = 0x0103
    HEADER_SIZE = 16
    chunk_size = 24

    return struct.pack('<HHI I i i i',
        END_TYPE,
        HEADER_SIZE,
        chunk_size,
        line_num,
        -1,            # comment
        -1,            # namespace
        name_idx       # name
    )

def main():
    # String indices
    STR_WEBVIEWPROVIDERS = 0    # "webviewproviders"
    STR_DESCRIPTION = 1         # "description"
    STR_PACKAGENAME = 2         # "packageName"
    STR_AVAILABLEBYDEFAULT = 3  # "availableByDefault"
    STR_WEBVIEWPROVIDER = 4     # "webviewprovider"
    STR_ANDROID_WEBVIEW = 5     # "Android WebView"
    STR_COM_ANDROID_WEBVIEW = 6 # "com.android.webview"
    STR_TRUE = 7                # "true"
    STR_CHROME = 8              # "Chrome" (NEW)
    STR_COM_ANDROID_CHROME = 9  # "com.android.chrome" (NEW)

    strings = [
        "webviewproviders",
        "description",
        "packageName",
        "availableByDefault",
        "webviewprovider",
        "Android WebView",
        "com.android.webview",
        "true",
        "Chrome",
        "com.android.chrome",
    ]

    TYPE_STRING = 0x03
    TYPE_BOOL = 0x12

    # Build chunks
    string_pool = build_string_pool(strings)

    # START webviewproviders (line 17)
    start_providers = build_start_element(17, STR_WEBVIEWPROVIDERS, [])

    # START webviewprovider - Chrome (line 18)
    chrome_attrs = [
        (-1, STR_DESCRIPTION, STR_CHROME, TYPE_STRING, STR_CHROME),
        (-1, STR_PACKAGENAME, STR_COM_ANDROID_CHROME, TYPE_STRING, STR_COM_ANDROID_CHROME),
        (-1, STR_AVAILABLEBYDEFAULT, STR_TRUE, TYPE_BOOL, 0xFFFFFFFF),
    ]
    start_chrome = build_start_element(18, STR_WEBVIEWPROVIDER, chrome_attrs)
    end_chrome = build_end_element(18, STR_WEBVIEWPROVIDER)

    # START webviewprovider - Android WebView (line 19)
    webview_attrs = [
        (-1, STR_DESCRIPTION, STR_ANDROID_WEBVIEW, TYPE_STRING, STR_ANDROID_WEBVIEW),
        (-1, STR_PACKAGENAME, STR_COM_ANDROID_WEBVIEW, TYPE_STRING, STR_COM_ANDROID_WEBVIEW),
        (-1, STR_AVAILABLEBYDEFAULT, STR_TRUE, TYPE_BOOL, 0xFFFFFFFF),
    ]
    start_webview = build_start_element(19, STR_WEBVIEWPROVIDER, webview_attrs)
    end_webview = build_end_element(20, STR_WEBVIEWPROVIDER)

    # END webviewproviders (line 21)
    end_providers = build_end_element(21, STR_WEBVIEWPROVIDERS)

    # Combine all chunks (after file header)
    body = (string_pool + start_providers +
            start_chrome + end_chrome +
            start_webview + end_webview +
            end_providers)

    # File header
    XML_TYPE = 0x0003
    FILE_HEADER_SIZE = 8
    total_size = FILE_HEADER_SIZE + len(body)

    file_header = struct.pack('<HHI', XML_TYPE, FILE_HEADER_SIZE, total_size)

    output = file_header + body

    out_path = sys.argv[1] if len(sys.argv) > 1 else 'config_webview_packages_patched.xml'
    with open(out_path, 'wb') as f:
        f.write(output)

    print(f"Written {len(output)} bytes to {out_path}")
    print(f"Original: 384 bytes, New: {len(output)} bytes")
    print(f"Strings: {len(strings)}")
    print(f"Chrome provider added before Android WebView provider")

if __name__ == '__main__':
    main()
