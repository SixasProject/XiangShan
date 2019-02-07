package core

import chisel3.iotesters.PeekPokeTester

import java.nio.{IntBuffer, ByteOrder}
import java.io.FileInputStream
import java.nio.channels.FileChannel

class NOOPTester(noop: NOOP, imgPath: String) extends PeekPokeTester(noop) {
  val memSize = 128 * 1024 * 1024
  val mem = {
    if (imgPath == "") {
      Array(
        0x07b08093,   // addi x1,x1,123
        0xf8508093,   // addi x1,x1,-123
        0x0000806b,   // trap x1
        0, 0, 0, 0
      )
    }
    else {
      val fc = new FileInputStream(imgPath).getChannel()
      println(f"bin size = 0x${fc.size()}%08x")

      var mem = Array.fill(memSize / 4)(0)
      fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size()).order(ByteOrder.LITTLE_ENDIAN)
        .asIntBuffer().get(mem, 0, fc.size() / 4)
      mem
    }
  }

  var pc = 0
  var trap = 0
  var instr = 0
  do {
    pc = peek(noop.io.imem.out.bits.addr).toInt
    assert((pc & 0x3) == 0)
    instr = mem(pc >> 2)
    poke(noop.io.imem.in.rdata, instr)

    step(1)

    trap = peek(noop.io.trap).toInt
  } while (trap == 3)

  trap match {
    case 0 => println(f"\33[1;32mHIT GOOD TRAP\33[0m at pc = 0x$pc%08x")
    case 1 => println(f"\33[1;31mHIT BAD TRAP\33[0m at pc = 0x$pc%08x")
    case 2 => println(f"\33[1;31mINVALID OPCODE\33[0m at pc = 0x$pc%08x, instr = 0x$instr%08x")
  }

  expect(noop.io.trap, 0)
}