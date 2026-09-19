/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Define AArch64 target-specific operand constraints.
 * Copyright (c) 2021 Linaro
 */

/*
 * Define constraint letters for register sets:
 * REGS(letter, register_mask)
 */
REGS('r', ALL_GENERAL_REGS)
REGS('w', ALL_VECTOR_REGS)
/*
 * Fixed registers for the out-of-lined memory-access stubs: the address (and
 * a loaded value) live in X0, a stored value in X1.  Constraining the operands
 * lets the call site be a bare BL with no register moves.  Selected
 * dynamically -- see cset_qemu_ld/cset_qemu_st -- so the normal inline path is
 * unconstrained when the stubs are off.
 */
REGS('k', 1u << TCG_REG_X0)
REGS('j', 1u << TCG_REG_X1)

/*
 * Define constraint letters for constants:
 * CONST(letter, TCG_CT_CONST_* bit set)
 */
CONST('A', TCG_CT_CONST_AIMM)
CONST('C', TCG_CT_CONST_CMP)
CONST('L', TCG_CT_CONST_LIMM)
CONST('M', TCG_CT_CONST_MONE)
CONST('O', TCG_CT_CONST_ORRI)
CONST('N', TCG_CT_CONST_ANDI)
CONST('Z', TCG_CT_CONST_ZERO)
