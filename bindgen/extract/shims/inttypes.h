/* Shim for freestanding cross-triple parsing (extract_api.py).
 *
 * clang's resource-dir inttypes.h is a fixup wrapper that unconditionally #include_next's the
 * libc header, which does not exist when parsing non-host triples. slang.h only needs the
 * fixed-width integer types from it; clang's stdint.h is self-contained under -ffreestanding.
 */
#pragma once
#include <stdint.h>
