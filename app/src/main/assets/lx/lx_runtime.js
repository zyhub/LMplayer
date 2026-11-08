/*
 * LMPlayer 本地音源运行时（QuickJS）。
 * 目标：与柠檬服务端 server/sourceManager.js + server/utils/lxSourceRuntime.js 提供的落雪(LX)
 * 自定义音源环境对齐，让在服务器上能用的脚本在本地也能用。
 *
 * 约定的 Kotlin 侧 __native 绑定（全部不会抛异常；同步函数出错时返回以 "\u0000ERR:" 开头的字符串）：
 *   request(url, optionsJson) -> Promise<string>   sleep(ms) -> Promise<void>   abort(rid)
 *   md5(str)  hash(alg, b64)  hmac(alg, keyB64, dataB64)  aes(encrypt, alg, keyB64, ivB64, dataB64, autoPadding)
 *   rsa(dataB64, pem, noPadding)  randomBytes(n)  zlib(op, mode, b64)  scriptInfo()  log(...)
 */
(function () {
  var G = globalThis;
  G.global = G;
  G.window = G;
  G.self = G;

  var ERR = '\u0000ERR:';
  function unwrap(v) {
    if (typeof v === 'string' && v.indexOf(ERR) === 0) throw new Error(v.substring(ERR.length));
    return v;
  }

  // ---------------- base64 / utf8 ----------------
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var B64R = {};
  for (var bi = 0; bi < 64; bi++) B64R[B64.charAt(bi)] = bi;
  B64R['-'] = 62;
  B64R['_'] = 63;

  function bytesToB64(u8) {
    var out = [];
    var i = 0;
    var n = u8.length;
    for (; i + 2 < n; i += 3) {
      var v = (u8[i] << 16) | (u8[i + 1] << 8) | u8[i + 2];
      out.push(B64.charAt((v >> 18) & 63), B64.charAt((v >> 12) & 63), B64.charAt((v >> 6) & 63), B64.charAt(v & 63));
    }
    if (i < n) {
      var v2 = u8[i] << 16;
      if (i + 1 < n) v2 |= u8[i + 1] << 8;
      out.push(B64.charAt((v2 >> 18) & 63), B64.charAt((v2 >> 12) & 63));
      out.push(i + 1 < n ? B64.charAt((v2 >> 6) & 63) : '=');
      out.push('=');
    }
    return out.join('');
  }

  function b64ToBytes(s) {
    s = String(s == null ? '' : s);
    var out = new Uint8Array(Math.floor(s.length * 3 / 4) + 3);
    var j = 0;
    var buf = 0;
    var bits = 0;
    for (var i = 0; i < s.length; i++) {
      var c = B64R[s.charAt(i)];
      if (c === undefined) continue;
      buf = ((buf << 6) | c) & 0xffffff;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out[j++] = (buf >> bits) & 255;
      }
    }
    return out.subarray(0, j);
  }

  function utf8Encode(str) {
    str = String(str);
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 0x80) {
        out.push(c);
      } else if (c < 0x800) {
        out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      } else if (c >= 0xd800 && c < 0xdc00 && i + 1 < str.length) {
        var c2 = str.charCodeAt(i + 1);
        if (c2 >= 0xdc00 && c2 < 0xe000) {
          var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
          i++;
          out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
        } else {
          out.push(0xef, 0xbf, 0xbd);
        }
      } else {
        out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
    }
    return new Uint8Array(out);
  }

  function utf8Decode(u8) {
    var parts = [];
    var chunk = [];
    var i = 0;
    while (i < u8.length) {
      var c = u8[i++];
      if (c < 0x80) {
        chunk.push(c);
      } else if (c >= 0xc0 && c < 0xe0) {
        chunk.push(((c & 31) << 6) | (u8[i++] & 63));
      } else if (c >= 0xe0 && c < 0xf0) {
        chunk.push(((c & 15) << 12) | ((u8[i++] & 63) << 6) | (u8[i++] & 63));
      } else if (c >= 0xf0) {
        var cp = ((c & 7) << 18) | ((u8[i++] & 63) << 12) | ((u8[i++] & 63) << 6) | (u8[i++] & 63);
        cp -= 0x10000;
        chunk.push(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023));
      } else {
        chunk.push(0xfffd);
      }
      if (chunk.length >= 4096) {
        parts.push(String.fromCharCode.apply(null, chunk));
        chunk = [];
      }
    }
    if (chunk.length) parts.push(String.fromCharCode.apply(null, chunk));
    return parts.join('');
  }

  function latin1Encode(str) {
    str = String(str);
    var out = new Uint8Array(str.length);
    for (var i = 0; i < str.length; i++) out[i] = str.charCodeAt(i) & 255;
    return out;
  }

  function latin1Decode(u8) {
    var parts = [];
    for (var i = 0; i < u8.length; i += 4096) {
      parts.push(String.fromCharCode.apply(null, Array.prototype.slice.call(u8.subarray(i, i + 4096))));
    }
    return parts.join('');
  }

  function hexEncode(u8) {
    var out = [];
    for (var i = 0; i < u8.length; i++) out.push((u8[i] < 16 ? '0' : '') + u8[i].toString(16));
    return out.join('');
  }

  function hexDecode(s) {
    s = String(s);
    var out = new Uint8Array(Math.floor(s.length / 2));
    for (var i = 0; i < out.length; i++) out[i] = parseInt(s.substr(i * 2, 2), 16) & 255;
    return out;
  }

  function strToBytes(s, enc) {
    enc = String(enc || 'utf8').toLowerCase();
    if (enc === 'hex') return hexDecode(s);
    if (enc === 'base64' || enc === 'base64url') return b64ToBytes(s);
    if (enc === 'binary' || enc === 'latin1' || enc === 'ascii') return latin1Encode(s);
    return utf8Encode(s);
  }

  function bytesToStr(u8, enc) {
    enc = String(enc || 'utf8').toLowerCase();
    if (enc === 'hex') return hexEncode(u8);
    if (enc === 'base64') return bytesToB64(u8);
    if (enc === 'base64url') return bytesToB64(u8).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+/g, '');
    if (enc === 'binary' || enc === 'latin1' || enc === 'ascii') return latin1Decode(u8);
    return utf8Decode(u8);
  }

  // ---------------- Buffer ----------------
  class Buffer extends Uint8Array {
    static from(v, enc, len) {
      if (typeof v === 'string') return Buffer.wrapBytes(strToBytes(v, enc));
      if (v instanceof ArrayBuffer) return Buffer.wrapBytes(new Uint8Array(v, enc || 0, len === undefined ? undefined : len));
      if (ArrayBuffer.isView(v)) return Buffer.wrapBytes(new Uint8Array(v.buffer, v.byteOffset, v.byteLength));
      if (Array.isArray(v)) return Buffer.wrapBytes(Uint8Array.from(v));
      if (v && v.type === 'Buffer' && Array.isArray(v.data)) return Buffer.wrapBytes(Uint8Array.from(v.data));
      return Buffer.wrapBytes(strToBytes(String(v), enc));
    }
    static wrapBytes(u8) {
      var b = new Buffer(u8.length);
      b.set(u8);
      return b;
    }
    static alloc(n, fill) {
      var b = new Buffer(Number(n) || 0);
      if (fill !== undefined) b.fill(typeof fill === 'number' ? fill : 0);
      return b;
    }
    static allocUnsafe(n) {
      return new Buffer(Number(n) || 0);
    }
    static isBuffer(o) {
      return o instanceof Buffer;
    }
    static isEncoding(enc) {
      return ['utf8', 'utf-8', 'hex', 'base64', 'base64url', 'binary', 'latin1', 'ascii'].indexOf(String(enc).toLowerCase()) >= 0;
    }
    static byteLength(s, enc) {
      if (typeof s !== 'string') return s ? s.byteLength || s.length || 0 : 0;
      return strToBytes(s, enc).length;
    }
    static concat(list, total) {
      list = list || [];
      var len = 0;
      for (var i = 0; i < list.length; i++) len += list[i].length;
      if (total !== undefined) len = Math.min(len, total);
      var out = new Buffer(len);
      var off = 0;
      for (var k = 0; k < list.length && off < len; k++) {
        var part = list[k] instanceof Uint8Array ? list[k] : Buffer.from(list[k]);
        var take = Math.min(part.length, len - off);
        out.set(part.subarray(0, take), off);
        off += take;
      }
      return out;
    }
    toString(enc, start, end) {
      var u = (start !== undefined || end !== undefined)
        ? this.subarray(start || 0, end === undefined ? this.length : end)
        : this;
      return bytesToStr(u, enc);
    }
    toJSON() {
      return { type: 'Buffer', data: Array.prototype.slice.call(this) };
    }
    equals(other) {
      if (!other || other.length !== this.length) return false;
      for (var i = 0; i < this.length; i++) if (this[i] !== other[i]) return false;
      return true;
    }
    write(str, offset, enc) {
      var bytes = strToBytes(str, enc);
      offset = offset || 0;
      var n = Math.min(bytes.length, this.length - offset);
      this.set(bytes.subarray(0, n), offset);
      return n;
    }
    readUInt8(o) { return this[o || 0]; }
    readUInt16BE(o) { o = o || 0; return (this[o] << 8) | this[o + 1]; }
    readUInt32BE(o) { o = o || 0; return ((this[o] << 24) >>> 0) + ((this[o + 1] << 16) | (this[o + 2] << 8) | this[o + 3]); }
    readUInt32LE(o) { o = o || 0; return ((this[o + 3] << 24) >>> 0) + ((this[o + 2] << 16) | (this[o + 1] << 8) | this[o]); }
  }
  G.Buffer = Buffer;

  function toB64(x) {
    if (x == null) return '';
    if (x instanceof Uint8Array) return bytesToB64(x);
    if (x instanceof ArrayBuffer) return bytesToB64(new Uint8Array(x));
    if (ArrayBuffer.isView(x)) return bytesToB64(new Uint8Array(x.buffer, x.byteOffset, x.byteLength));
    return bytesToB64(utf8Encode(String(x)));
  }
  function toBytes(x, enc) {
    if (x instanceof Uint8Array) return x;
    if (x instanceof ArrayBuffer) return new Uint8Array(x);
    if (ArrayBuffer.isView(x)) return new Uint8Array(x.buffer, x.byteOffset, x.byteLength);
    return strToBytes(String(x == null ? '' : x), enc);
  }
  function fromB64(b64) {
    return Buffer.wrapBytes(b64ToBytes(unwrap(b64)));
  }

  G.atob = function (s) { return latin1Decode(b64ToBytes(s)); };
  G.btoa = function (s) { return bytesToB64(latin1Encode(s)); };

  class TextEncoder {
    get encoding() { return 'utf-8'; }
    encode(s) { return utf8Encode(s === undefined ? '' : s); }
  }
  class TextDecoder {
    constructor(label) { this.label = label || 'utf-8'; }
    get encoding() { return 'utf-8'; }
    decode(u8) {
      if (!u8) return '';
      var bytes = toBytes(u8);
      var lower = String(this.label).toLowerCase();
      if (lower === 'latin1' || lower === 'iso-8859-1' || lower === 'ascii') return latin1Decode(bytes);
      return utf8Decode(bytes);
    }
  }
  if (typeof G.TextEncoder === 'undefined') G.TextEncoder = TextEncoder;
  if (typeof G.TextDecoder === 'undefined') G.TextDecoder = TextDecoder;

  // ---------------- querystring / URLSearchParams / URL ----------------
  function qsEscape(s) {
    return encodeURIComponent(String(s)).replace(/[!'()*]/g, function (c) {
      return '%' + c.charCodeAt(0).toString(16).toUpperCase();
    });
  }
  function qsUnescape(s) {
    try { return decodeURIComponent(String(s).replace(/\+/g, ' ')); } catch (e) { return String(s); }
  }
  var querystring = {
    escape: qsEscape,
    unescape: qsUnescape,
    stringify: function (obj, sep, eq) {
      sep = sep || '&';
      eq = eq || '=';
      if (!obj || typeof obj !== 'object') return '';
      var out = [];
      Object.keys(obj).forEach(function (k) {
        var v = obj[k];
        if (Array.isArray(v)) v.forEach(function (x) { out.push(qsEscape(k) + eq + qsEscape(x == null ? '' : x)); });
        else out.push(qsEscape(k) + eq + qsEscape(v == null ? '' : v));
      });
      return out.join(sep);
    },
    parse: function (str, sep, eq) {
      sep = sep || '&';
      eq = eq || '=';
      var obj = {};
      String(str || '').split(sep).forEach(function (pair) {
        if (!pair) return;
        var idx = pair.indexOf(eq);
        var k = qsUnescape(idx >= 0 ? pair.substring(0, idx) : pair);
        var v = idx >= 0 ? qsUnescape(pair.substring(idx + 1)) : '';
        if (Object.prototype.hasOwnProperty.call(obj, k)) {
          if (Array.isArray(obj[k])) obj[k].push(v);
          else obj[k] = [obj[k], v];
        } else {
          obj[k] = v;
        }
      });
      return obj;
    },
  };
  querystring.encode = querystring.stringify;
  querystring.decode = querystring.parse;

  class URLSearchParams {
    constructor(init) {
      this._list = [];
      if (init == null) return;
      if (typeof init === 'string') {
        var s = init.charAt(0) === '?' ? init.substring(1) : init;
        var self = this;
        s.split('&').forEach(function (pair) {
          if (!pair) return;
          var idx = pair.indexOf('=');
          self._list.push([qsUnescape(idx >= 0 ? pair.substring(0, idx) : pair), idx >= 0 ? qsUnescape(pair.substring(idx + 1)) : '']);
        });
      } else if (Array.isArray(init)) {
        for (var i = 0; i < init.length; i++) this._list.push([String(init[i][0]), String(init[i][1])]);
      } else if (typeof init === 'object') {
        var keys = Object.keys(init);
        for (var k = 0; k < keys.length; k++) this._list.push([keys[k], String(init[keys[k]])]);
      }
    }
    append(k, v) { this._list.push([String(k), String(v)]); }
    delete(k) { this._list = this._list.filter(function (e) { return e[0] !== String(k); }); }
    get(k) { for (var i = 0; i < this._list.length; i++) if (this._list[i][0] === String(k)) return this._list[i][1]; return null; }
    getAll(k) { return this._list.filter(function (e) { return e[0] === String(k); }).map(function (e) { return e[1]; }); }
    has(k) { return this.get(k) !== null; }
    set(k, v) { this.delete(k); this.append(k, v); }
    forEach(fn, thisArg) { var self = this; this._list.forEach(function (e) { fn.call(thisArg, e[1], e[0], self); }); }
    keys() { return this._list.map(function (e) { return e[0]; })[Symbol.iterator](); }
    values() { return this._list.map(function (e) { return e[1]; })[Symbol.iterator](); }
    entries() { return this._list.map(function (e) { return [e[0], e[1]]; })[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
    toString() { return this._list.map(function (e) { return qsEscape(e[0]) + '=' + qsEscape(e[1]); }).join('&'); }
  }

  class URL {
    constructor(input, base) {
      var s = String(input);
      if (base !== undefined && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(s)) {
        var b = new URL(String(base));
        if (s.indexOf('//') === 0) s = b.protocol + s;
        else if (s.charAt(0) === '/') s = b.origin + s;
        else s = b.origin + b.pathname.replace(/[^/]*$/, '') + s;
      }
      var m = /^([a-zA-Z][a-zA-Z0-9+.-]*:)\/\/(?:([^:@/]*)(?::([^@/]*))?@)?([^:/?#]*)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?/.exec(s);
      if (!m) throw new TypeError('Invalid URL: ' + s);
      this.protocol = m[1].toLowerCase();
      this.username = m[2] || '';
      this.password = m[3] || '';
      this.hostname = m[4] || '';
      this.port = m[5] || '';
      this.pathname = m[6] || '/';
      this.hash = m[8] || '';
      this.searchParams = new URLSearchParams(m[7] || '');
    }
    get host() { return this.hostname + (this.port ? ':' + this.port : ''); }
    get origin() { return this.protocol + '//' + this.host; }
    get search() { var q = this.searchParams.toString(); return q ? '?' + q : ''; }
    set search(v) { this.searchParams = new URLSearchParams(String(v || '')); }
    get href() {
      var auth = this.username ? this.username + (this.password ? ':' + this.password : '') + '@' : '';
      return this.protocol + '//' + auth + this.host + this.pathname + this.search + this.hash;
    }
    toString() { return this.href; }
    toJSON() { return this.href; }
  }
  if (typeof G.URLSearchParams === 'undefined') G.URLSearchParams = URLSearchParams;
  if (typeof G.URL === 'undefined') G.URL = URL;

  // ---------------- timers ----------------
  // quickjs-kt 的 evaluate 会等待全部原生异步任务结束才返回：
  // 1. 超过 MAX_TIMER_MS 的定时器不调度（多为脚本的更新检查/心跳），否则会把求值拖住；
  // 2. setInterval 只登记不触发，避免永不结束的求值。
  var MAX_TIMER_MS = 10000;
  var timers = new Map();
  var timerSeq = 0;
  G.setTimeout = function (fn, ms) {
    var args = Array.prototype.slice.call(arguments, 2);
    var id = ++timerSeq;
    var delay = Math.max(0, Number(ms) || 0);
    if (typeof fn !== 'function') return id;
    if (delay > MAX_TIMER_MS) {
      __native.log('setTimeout(' + delay + 'ms) skipped');
      return id;
    }
    timers.set(id, true);
    __native.sleep(delay).then(function () {
      if (!timers.delete(id)) return;
      try { fn.apply(G, args); } catch (e) { __native.log('timer error: ' + (e && e.message || e)); }
    });
    return id;
  };
  G.clearTimeout = function (id) { timers.delete(id); };
  G.setInterval = function () { return ++timerSeq; };
  G.clearInterval = function () {};
  G.setImmediate = function (fn) {
    var args = Array.prototype.slice.call(arguments, 1);
    return G.setTimeout.apply(G, [fn, 0].concat(args));
  };
  G.clearImmediate = G.clearTimeout;
  G.queueMicrotask = function (fn) { Promise.resolve().then(fn); };

  // ---------------- crypto / zlib ----------------
  var HASH_ALGS = { md5: 'MD5', sha1: 'SHA-1', sha256: 'SHA-256', sha512: 'SHA-512', 'sha-1': 'SHA-1', 'sha-256': 'SHA-256' };
  function hashAlg(alg) {
    var a = HASH_ALGS[String(alg || '').toLowerCase()];
    if (!a) throw new Error('Digest method not supported: ' + alg);
    return a;
  }
  function digestOut(b64, enc) {
    var buf = fromB64(b64);
    return enc ? buf.toString(enc) : buf;
  }
  function makeHash(alg, key) {
    var javaAlg = hashAlg(alg);
    var parts = [];
    return {
      update: function (data, enc) { parts.push(toBytes(data, enc)); return this; },
      digest: function (enc) {
        var all = toB64(Buffer.concat(parts));
        return digestOut(key == null ? __native.hash(javaAlg, all) : __native.hmac(javaAlg, toB64(key), all), enc);
      },
    };
  }
  function makeCipher(encrypt, alg, key, iv) {
    var parts = [];
    var autoPadding = true;
    return {
      setAutoPadding: function (v) { autoPadding = v !== false; return this; },
      update: function (data, inEnc, outEnc) {
        parts.push(toBytes(data, inEnc));
        return outEnc ? '' : Buffer.alloc(0);
      },
      final: function (outEnc) {
        var out = fromB64(__native.aes(encrypt, String(alg), toB64(key), iv == null ? '' : toB64(iv), toB64(Buffer.concat(parts)), autoPadding));
        return outEnc ? out.toString(outEnc) : out;
      },
    };
  }
  var RSA_PKCS1_PADDING = 1;
  var RSA_NO_PADDING = 3;
  var nodeCrypto = {
    createHash: function (alg) { return makeHash(alg, null); },
    createHmac: function (alg, key) { return makeHash(alg, key); },
    createCipheriv: function (alg, key, iv) { return makeCipher(true, alg, key, iv); },
    createDecipheriv: function (alg, key, iv) { return makeCipher(false, alg, key, iv); },
    randomBytes: function (n) { return fromB64(__native.randomBytes(Number(n) || 0)); },
    publicEncrypt: function (opts, data) {
      var key = typeof opts === 'string' ? opts : (opts && opts.key);
      var padding = typeof opts === 'object' && opts && opts.padding !== undefined ? opts.padding : RSA_PKCS1_PADDING;
      return fromB64(__native.rsa(toB64(data), String(key), padding === RSA_NO_PADDING));
    },
    constants: { RSA_PKCS1_PADDING: RSA_PKCS1_PADDING, RSA_NO_PADDING: RSA_NO_PADDING },
  };
  function zlibCall(op, mode, buf) {
    return fromB64(__native.zlib(op, mode, toB64(buf)));
  }
  function zlibAsync(op, mode) {
    return function (buf, opts, cb) {
      if (typeof opts === 'function') cb = opts;
      var out;
      var err = null;
      try { out = zlibCall(op, mode, buf); } catch (e) { err = e; }
      if (typeof cb === 'function') {
        G.setTimeout(function () { cb(err, out); }, 0);
        return undefined;
      }
      return err ? Promise.reject(err) : Promise.resolve(out);
    };
  }
  var nodeZlib = {
    inflate: zlibAsync('inflate', 'zlib'),
    inflateRaw: zlibAsync('inflate', 'raw'),
    gunzip: zlibAsync('inflate', 'gzip'),
    unzip: zlibAsync('inflate', 'auto'),
    deflate: zlibAsync('deflate', 'zlib'),
    deflateRaw: zlibAsync('deflate', 'raw'),
    gzip: zlibAsync('deflate', 'gzip'),
    inflateSync: function (b) { return zlibCall('inflate', 'zlib', b); },
    inflateRawSync: function (b) { return zlibCall('inflate', 'raw', b); },
    gunzipSync: function (b) { return zlibCall('inflate', 'gzip', b); },
    unzipSync: function (b) { return zlibCall('inflate', 'auto', b); },
    deflateSync: function (b) { return zlibCall('deflate', 'zlib', b); },
    deflateRawSync: function (b) { return zlibCall('deflate', 'raw', b); },
    gzipSync: function (b) { return zlibCall('deflate', 'gzip', b); },
  };

  var lxUtils = {
    crypto: {
      aesEncrypt: function (buffer, mode, key, iv) {
        return fromB64(__native.aes(true, String(mode || ''), toB64(key), iv == null || iv === '' ? '' : toB64(iv), toB64(buffer), true));
      },
      rsaEncrypt: function (buffer, key) {
        var data = toBytes(buffer);
        if (data.length < 128) {
          var padded = new Uint8Array(128);
          padded.set(data, 128 - data.length);
          data = padded;
        }
        return fromB64(__native.rsa(toB64(data), String(key), true));
      },
      randomBytes: function (size) { return fromB64(__native.randomBytes(Number(size) || 0)); },
      md5: function (str) { return unwrap(__native.md5(String(str))); },
    },
    buffer: {
      from: function () { return Buffer.from.apply(Buffer, arguments); },
      concat: function () { return Buffer.concat.apply(Buffer, arguments); },
      bufToString: function (buf, format) { return Buffer.from(buf, 'binary').toString(format); },
    },
    zlib: {
      inflate: function (buf) { return nodeZlib.inflate(buf); },
      deflate: function (buf) { return nodeZlib.deflate(buf); },
    },
  };

  G.require = function (name) {
    var key = String(name || '').replace(/^node:/, '');
    if (key === 'crypto') return nodeCrypto;
    if (key === 'zlib') return nodeZlib;
    if (key === 'buffer') return { Buffer: Buffer };
    if (key === 'querystring') return querystring;
    if (key === 'url') return { URL: URL, URLSearchParams: URLSearchParams };
    throw new Error("Cannot find module '" + name + "'");
  };
  G.process = { env: {}, version: 'v18.0.0', versions: {}, platform: 'android', nextTick: function (fn) { Promise.resolve().then(fn); } };
  var moduleExports = {};
  G.module = { exports: moduleExports };
  G.exports = moduleExports;

  // ---------------- console ----------------
  function logArgs() {
    try { __native.log(Array.prototype.map.call(arguments, function (a) { return typeof a === 'string' ? a : JSON.stringify(a); }).join(' ')); } catch (e) {}
  }
  G.console = { log: logArgs, info: logArgs, warn: logArgs, error: logArgs, debug: function () {}, group: function () {}, groupEnd: function () {} };

  // ---------------- lx ----------------
  G.__handlers = {};
  G.__sent = {};
  G.__updateAlert = null;
  var scriptInfo = {};
  try { scriptInfo = JSON.parse(__native.scriptInfo() || '{}'); } catch (e) {}

  var reqSeq = 0;
  function isBytes(x) { return x instanceof Uint8Array || x instanceof ArrayBuffer || ArrayBuffer.isView(x); }

  function lxRequest(url, options, callback) {
    if (typeof options === 'function') {
      callback = options;
      options = {};
    }
    options = options || {};
    var rid = 'r' + (++reqSeq);
    var opts = { rid: rid, method: String(options.method || 'get').toUpperCase(), headers: options.headers || {} };
    if (options.timeout != null) opts.timeout = Number(options.timeout) || 0;
    if (options.body != null) {
      if (typeof options.body === 'string') opts.body = options.body;
      else if (isBytes(options.body)) opts.bodyBase64 = toB64(options.body);
      else { opts.body = JSON.stringify(options.body); opts.bodyIsJson = true; }
    } else if (options.form != null) {
      opts.form = typeof options.form === 'string' ? options.form : querystring.stringify(options.form);
    } else if (options.formData != null) {
      opts.formData = options.formData;
    }
    if (options.binary || options.responseType === 'arraybuffer' || options.responseType === 'buffer') opts.binary = true;
    var promise = __native.request(String(url), JSON.stringify(opts)).then(function (raw) {
      var r = JSON.parse(raw);
      if (r.error) throw new Error(r.error);
      var resp = r.response;
      var body = resp.body;
      if (resp.bodyBase64 != null) {
        body = Buffer.from(resp.bodyBase64, 'base64');
        delete resp.bodyBase64;
      } else if (typeof body === 'string') {
        resp.raw = body;
        try { body = JSON.parse(body); } catch (e) {}
      }
      resp.body = body;
      return resp;
    });
    var abort = function () { try { __native.abort(rid); } catch (e) {} };
    if (typeof callback === 'function') {
      promise.then(function (resp) {
        try { callback.call(G, null, resp, resp.body); } catch (e) { __native.log('request callback error: ' + (e && e.message || e)); }
      }, function (err) {
        try { callback.call(G, err, null, null); } catch (e) { __native.log('request callback error: ' + (e && e.message || e)); }
      });
      return abort;
    }
    // 非标准用法兼容：不传回调时返回 Promise（附带 abort）
    promise.abort = abort;
    return promise;
  }

  G.lx = {
    version: '2.0.0',
    env: 'desktop',
    EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
    currentScriptInfo: {
      name: scriptInfo.name || '',
      description: scriptInfo.description || '',
      version: scriptInfo.version || '',
      author: scriptInfo.author || '',
      homepage: scriptInfo.homepage || '',
      rawScript: '',
    },
    on: function (event, handler) { G.__handlers[event] = handler; return Promise.resolve(); },
    send: function (event, data) {
      if (event === 'updateAlert') G.__updateAlert = data || null;
      else G.__sent[event] = data;
      return Promise.resolve();
    },
    request: lxRequest,
    utils: lxUtils,
  };

  // 统一的请求入口：兼容 async 返回、回调返回(payload, callback)、同步返回三种写法（对齐服务端 invokeHandler）
  function normalizeCallbackError(err) {
    if (err == null || err === 0 || err === false) return null;
    if (err instanceof Error) return err;
    if (typeof err === 'number') return new Error('音源请求失败(' + err + ')');
    if (typeof err === 'string' && err.trim()) return new Error(err);
    return new Error(String(err));
  }
  G.__lmInvoke = function (payloadJson) {
    G.__lmResult = null;
    var payload = JSON.parse(payloadJson);
    var settled = false;
    function finish(err, data) {
      if (settled) return;
      settled = true;
      if (err) G.__lmResult = { ok: false, error: String((err && err.message) || err) };
      else G.__lmResult = { ok: true, value: data };
    }
    var handler = G.__handlers[G.lx.EVENT_NAMES.request];
    if (typeof handler !== 'function') {
      finish(new Error('no request handler'));
      return;
    }
    try {
      var r = handler(payload, function (err, data) {
        var e = normalizeCallbackError(err);
        if (e) finish(e);
        else finish(null, data);
      });
      if (r && typeof r.then === 'function') {
        r.then(function (d) {
          if (d === undefined || d === null) return;
          finish(null, d);
        }, function (e) { finish(e || new Error('handler rejected')); });
      } else if (r != null && (typeof r === 'string' || typeof r === 'object')) {
        finish(null, r);
      }
    } catch (e) {
      finish(e);
    }
  };
  G.__lmResultJson = function () {
    var r = G.__lmResult;
    if (!r) return JSON.stringify({ ok: false, error: '音源未返回结果' });
    try {
      return JSON.stringify(r);
    } catch (e) {
      return JSON.stringify({ ok: true, value: String(r.value) });
    }
  };
})();
