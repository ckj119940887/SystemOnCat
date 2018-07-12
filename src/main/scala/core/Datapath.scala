package systemoncat.core

import chisel3._
import chisel3.util._

class DatapathIO() extends Bundle {
    val ctrl = Flipped(new DecoderIO)
}

class Datapath() extends Module {
    val io = IO(new DatapathIO())

    val ex_ctrl_sigs = Reg(new ControlSignals)
    val mem_ctrl_sigs = Reg(new ControlSignals)
    val wb_ctrl_sigs = Reg(new ControlSignals)

    val ex_reg_valid = Reg(Bool()) // is a valid inst
    val mem_reg_valid = Reg(Bool())
    val wb_reg_valid = Reg(Bool())

    val ex_reg_inst = Reg(Bits()) // original instruction
    val mem_reg_inst = Reg(Bits())
    val wb_reg_inst = Reg(Bits())

    val ex_waddr = ex_reg_inst(11,7) // rd can be directly extracted from inst
    val mem_waddr = mem_reg_inst(11,7)
    val wb_waddr = wb_reg_inst(11,7)

    val ex_reg_cause = Reg(UInt(4.W)) // exception cause
    val mem_reg_cause = Reg(UInt(4.W))
    val wb_reg_cause = Reg(UInt(4.W))

    val id_reg_pc = Reg(UInt())
    val ex_reg_pc = Reg(UInt())
    val mem_reg_pc = Reg(UInt())
    val wb_reg_pc = Reg(UInt())

    val mem_reg_rs2 = Reg(UInt()) // used as store address

    val mem_reg_wdata = Reg(Bits()) // data for write back
    val wb_reg_wdata = Reg(Bits())

    val ex_reg_imme = Reg(UInt()) // 32 bit immediate, sign extended if necessary


    // ---------- NPC ----------
    val pc = RegInit(0.U(32.W)) // initial pc
    val npc = pc + 4.U
    pc := npc
    val inst_reg = RegInit(NOP) // instruction in IF

    // ---------- IF -----------
    val ifetch = Module(new IFetch)
    ifetch.io.pc := pc

    // ---------- ID -----------
    // regs update
    inst_reg := ifetch.io.inst
    io.ctrl.inst := inst_reg
    id_reg_pc := pc


    val id_rs1 = inst_reg(19, 15) // rs1
    val id_rs2 = inst_reg(24, 20) // rs2
    val id_rd  = inst_reg(11, 7)  // rd
    val id_imme = ImmGen(io.ctrl.sig.imm_sel, inst_reg) // immediate
    val id_ren = IndexedSeq(io.ctrl.sig.rxs1, io.ctrl.sig.rxs2)
    val regfile = Module(new RegFile)
    regfile.io.raddr1 := id_rs1
    regfile.io.raddr2 := id_rs2
    regfile.io.wen := wb_ctrl_sigs.wb_en
    regfile.io.waddr := wb_waddr
    val reg_write = Wire(UInt()) // assigned later
    regfile.io.wdata := reg_write

    val id_raddr = IndexedSeq(id_rs1, id_rs2)
    val id_rdatas = IndexedSeq(regfile.io.rdata1, regfile.io.rdata2)
    val bypass_sources = IndexedSeq( // has priority !
        (true.B, 0.U, 0.U), // x0 = 0
        (ex_reg_valid && ex_ctrl_sigs.wb_en, ex_waddr, mem_reg_wdata),
        (mem_reg_valid && mem_ctrl_sigs.wb_en && !mem_ctrl_sigs.mem, mem_waddr, wb_reg_wdata))

    val id_bypass_src = id_raddr.map(raddr => bypass_sources.map(s => s._1 && s._2 === raddr))

    // ---------- EXE ----------
    // regs update
    ex_ctrl_sigs := io.ctrl.sig
    ex_reg_imme := id_imme
    ex_reg_inst := inst_reg
    ex_reg_pc := id_reg_pc
    ex_reg_valid := true.B // TODO: check valid (stall logic related)

    // bypass logic
    val ex_reg_rs_bypass = Reg(Vec(id_raddr.size, Bool())) // if this reg can be bypassed
    val ex_reg_rdatas = Reg(Vec(id_raddr.size, Bits())) // reg datas
    val ex_reg_bypass_src = Reg(Vec(id_raddr.size, UInt(log2Ceil(bypass_sources.size).W))) // which bypass is taken
    val bypass_mux = Seq(
        0.U -> 0.U,
        1.U -> mem_reg_wdata,
        2.U -> wb_reg_wdata
    )
    for (i <- 0 until id_raddr.size) {
        val do_bypass = id_bypass_src(i).reduce(_ || _) // at least one bypass is possible
        val bypass_src = PriorityEncoder(id_bypass_src(i))
        ex_reg_rs_bypass(i) := do_bypass // bypass is checked at ID, but done in EXE
        ex_reg_bypass_src(i) := bypass_src
        ex_reg_rdatas(i) := id_rdatas(i)
    }

    val ex_rs = for (i <- 0 until id_raddr.size)
        yield Mux(ex_reg_rs_bypass(i), MuxLookup(ex_reg_bypass_src(i), ex_reg_rdatas(i), bypass_mux), ex_reg_rdatas(i))

    // alu logic
    val ex_op1 = MuxLookup(ex_ctrl_sigs.A1_sel, 0.S, Seq(
        A1_RS1 -> ex_rs(0).asSInt,
        A1_PC -> ex_reg_pc.asSInt))
    val ex_op2 = MuxLookup(ex_ctrl_sigs.A2_sel, 0.S, Seq(
        A2_RS2 -> ex_rs(1).asSInt,
        A2_IMM -> ex_reg_imme.asSInt,
        A2_SIZE -> 4.S))

    val alu = Module(new ALU)
    alu.io.fn := ex_ctrl_sigs.alu_op
    alu.io.in1 := ex_op1
    alu.io.in2 := ex_op2
    // alu.io.cmp_out decides whether a branch is taken

    // ---------- MEM ----------
    // regs update
    when (ex_reg_valid) {
        mem_ctrl_sigs := ex_ctrl_sigs
        mem_reg_pc := ex_reg_pc
        mem_reg_inst := ex_reg_inst
        mem_reg_rs2 := ex_rs(1)
        mem_reg_wdata := alu.io.out
        mem_reg_valid := ex_reg_valid // TODO: check valid (stall logic related)
    }
    
    val dmem = Module(new DMem)

    dmem.io.wr_data := mem_reg_rs2
    dmem.io.addr := mem_reg_wdata
    dmem.io.wr_en := mem_ctrl_sigs.mem && isWrite(mem_ctrl_sigs.mem_cmd)
    dmem.io.rd_en := mem_ctrl_sigs.mem && isRead(mem_ctrl_sigs.mem_cmd)
    dmem.io.mem_type := mem_ctrl_sigs.mem_type

    // ---------- WB -----------
    when (mem_reg_valid) {
        wb_ctrl_sigs := mem_ctrl_sigs
        wb_reg_pc := mem_reg_pc
        wb_reg_inst := mem_reg_inst
        wb_reg_wdata := mem_reg_wdata
    }

    reg_write := MuxLookup(wb_ctrl_sigs.wb_sel, wb_reg_wdata, Seq(
        WB_MEM -> dmem.io.rd_data,
        WB_PC4 -> (wb_reg_pc + 4.U),
        WB_ALU -> wb_reg_wdata
        // WB_CSR -> csr.io.out.zext) 
        )).asUInt

}