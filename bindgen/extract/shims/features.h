/* Empty shim for freestanding cross-triple parsing (extract_api.py).
 *
 * slang.h includes <features.h> on the Linux family solely to sniff __GLIBC__ for its
 * SLANG_HAS_BACKTRACE macro, which has no ABI effect. Leaving the macros undefined simply
 * takes the no-backtrace path.
 */
