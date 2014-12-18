package water.persist;

import java.io.*;
import java.net.URI;
import java.util.Arrays;

import water.*;
import water.fvec.HDFSFileVec;
import water.fvec.NFSFileVec;
import water.fvec.Vec;
import water.util.Log;

/** Abstract class describing various persistence targets.
 *  <p><ul>
 *  <li>{@link #store(Value v)} - Store a Value, using storage space.</li>
 *  <li>{@link #load(Value v)} - Load a previously stored Value.</li>
 *  <li>{@link #delete(Value v)} - Free storage from a previously store Value.</li>
 *  </ul>
 *  This class is used to implement both user-mode swapping, and the initial
 *  load of files - typically raw text for parsing.
 */
public abstract class Persist {
  /** All available back-ends, C.f. Value for indexes */
  public static final Persist[] I = new Persist[8];

  /** Persistence schemes; used as file prefixes eg "hdfs://some_hdfs_path/some_file" */
  public abstract static class Schemes {
    public static final String FILE = "file";
    public static final String HDFS = "hdfs";
    public static final String S3   = "s3";
    public static final String NFS  = "nfs";
  }

  static {
    Persist ice = null;
    URI uri = H2O.ICE_ROOT;
    if( uri != null ) { // Otherwise class loaded for reflection
      boolean windowsPath = uri.toString().matches("^[a-zA-Z]:.*");

      // System.out.println("TOM uri getPath(): " + uri.getPath());
      // System.out.println("TOM windowsPath: " + (windowsPath ? "true" : "false"));

      if ( windowsPath ) {
        ice = new PersistFS(new File(uri.toString()));
      }
      else if ((uri.getScheme() == null) || Schemes.FILE.equals(uri.getScheme())) {
        ice = new PersistFS(new File(uri.getPath()));
      }
      else if( Schemes.HDFS.equals(uri.getScheme()) ) {
        ice = new PersistHdfs(uri);
      }

      // System.out.println("TOM ice is null: " + ((ice == null) ? "true" : "false"));

      I[Value.ICE ] = ice;
      I[Value.HDFS] = new PersistHdfs();
      I[Value.S3  ] = new PersistS3();
      I[Value.NFS ] = new PersistNFS();
    }
  }

  /** Get the current Persist flavor for user-mode swapping. */
  public static Persist getIce() { return I[Value.ICE]; }

  static Persist get( byte p ) { return I[p]; }

  /** Store a Value into persistent storage, consuming some storage space. */
  abstract public void store(Value v);

  /** Load a previously stored Value */
  abstract public byte[] load(Value v) throws IOException;

  /** Reclaim space from a previously stored Value */
  abstract public void delete(Value v);

  /** Usable storage space, or -1 for unknown */
  public long getUsableSpace() { return /*UNKNOWN*/-1; }

  /** Total storage space, or -1 for unknown */
  public long getTotalSpace() { return /*UNKNOWN*/-1; }

  /** Transform given uri into file vector holding file name. */
  abstract public Key uriToKey(URI uri) throws IOException;

  public static final Key anyURIToKey(URI uri) throws IOException {
    Key ikey = null;
    String scheme = uri.getScheme();
    if ("hdfs".equals(scheme)) {
      ikey = I[Value.HDFS].uriToKey(uri);
    } else if ("s3n".equals(scheme)) {
      ikey = I[Value.HDFS].uriToKey(uri);
    } else if ("files".equals(scheme) || scheme == null) {
      ikey = I[Value.NFS].uriToKey(uri);
    }
    return ikey;
  }

  //the filename can be either byte encoded if it starts with % followed by
  // a number, or is a normal key name with special characters encoded in
  // special ways.
  // It is questionable whether we need this because the only keys we have on
  // ice are likely to be arraylet chunks

  static String getIceName(Value v) {
    return getIceName(v._key);
  }

  static String getIceName(Key k) {
    return getIceDirectory(k) + File.separator + key2Str(k);
  }

  static String getIceDirectory(Key key) {
    if( !key.isChunkKey() ) return "not_an_arraylet";
    // Reverse arraylet key generation
    byte[] b = key.getVecKey()._kb;
    return escapeBytes(b, 0, new StringBuilder(b.length)).toString();
  }

  // Verify bijection of key/file-name mappings.
  private static String key2Str(Key k) {
    String s = key2Str_impl(k);
    Key x;
    assert (x = str2Key_impl(s)).equals(k) : "bijection fail " + k + " <-> " + s + " <-> " + x;
    return s;
  }

  // Verify bijection of key/file-name mappings.
  static Key str2Key(String s) {
    Key k = str2Key_impl(s);
    assert key2Str_impl(k).equals(s) : "bijection fail " + s + " <-> " + k;
    return k;
  }

  // Convert a Key to a suitable filename string
  private static String key2Str_impl(Key k) {
    // check if we are system key
    StringBuilder sb = new StringBuilder(k._kb.length / 2 + 4);
    int i = 0;
    if( k._kb[0] < 32 ) {
      // System keys: hexalate all the leading non-ascii bytes
      sb.append('%');
      int j = k._kb.length - 1;     // Backwards scan for 1st non-ascii
      while( j >= 0 && k._kb[j] >= 32 && k._kb[j] < 128 )
        j--;
      for( ; i <= j; i++ ) {
        byte b = k._kb[i];
        int nib0 = ((b >>> 4) & 15) + '0';
        if( nib0 > '9' ) nib0 += 'A' - 10 - '0';
        int nib1 = ((b >>> 0) & 15) + '0';
        if( nib1 > '9' ) nib1 += 'A' - 10 - '0';
        sb.append((char) nib0).append((char) nib1);
      }
      sb.append('%');
    }
    // Escape the special bytes from 'i' to the end
    return escapeBytes(k._kb, i, sb).toString();
  }

  private static StringBuilder escapeBytes(byte[] bytes, int i, StringBuilder sb) {
    for( ; i < bytes.length; i++ ) {
      char b = (char)bytes[i], c=0;
      switch( b ) {
      case '%': c='%'; break;
      case '.': c='d'; break;
      case '/': c='s'; break;
      case ':': c='c'; break;
      case '"': c='q'; break;
      case '>': c='g'; break;
      case '\\':c='b'; break;
      case '\0':c='z'; break;
      }
      if( c!=0 ) sb.append('%').append(c);
      else sb.append(b);
    }
    return sb;
  }

  // Convert a filename string to a Key
  private static Key str2Key_impl(String s) {
    String key = s;
    byte[] kb = new byte[(key.length() - 1) / 2];
    int i = 0, j = 0;
    if( (key.length() > 2) && (key.charAt(0) == '%') && (key.charAt(1) >= '0') && (key.charAt(1) <= '9') ) {
      // Dehexalate until '%'
      for( i = 1; i < key.length(); i += 2 ) {
        if( key.charAt(i) == '%' ) break;
        char b0 = (char) (key.charAt(i    ) - '0');
        if( b0 > 9 ) b0 += '0' + 10 - 'A';
        char b1 = (char) (key.charAt(i + 1) - '0');
        if( b1 > 9 ) b1 += '0' + 10 - 'A';
        kb[j++] = (byte) ((b0 << 4) | b1);  // De-hexelated byte
      }
      i++;                      // Skip the trailing '%'
    }
    // a normal key - ASCII with special characters encoded after % sign
    for( ; i < key.length(); ++i ) {
      byte b = (byte) key.charAt(i);
      if( b == '%' ) {
        switch( key.charAt(++i) ) {
        case '%':  b = '%';  break;
        case 'c':  b = ':';  break;
        case 'd':  b = '.';  break;
        case 'g':  b = '>';  break;
        case 'q':  b = '"';  break;
        case 's':  b = '/';  break;
        case 'b':  b = '\\'; break;
        case 'z':  b = '\0'; break;
        default:
          Log.warn("Invalid format of filename " + s + " at index " + i);
        }
      }
      if( j >= kb.length ) kb = Arrays.copyOf(kb, Math.max(2, j * 2));
      kb[j++] = b;
    }
    // now in kb we have the key name
    return Key.make(Arrays.copyOf(kb, j));
  }
}
