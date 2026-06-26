// Simple synchronous circular-buffer FIFO, mirroring Chisel's Queue,
// used to buffer WeightScratchpad read responses ahead of the systolic array.
module WeightQueue #(parameter WIDTH = 128, DEPTH = 8) (
  input clk,
  input rst,

  input                  enq_valid,
  output                 enq_ready,
  input  [(WIDTH-1):0]   enq_data,

  output                 deq_valid,
  input                  deq_ready,
  output [(WIDTH-1):0]   deq_data
);

localparam PTR_W = $clog2(DEPTH);

reg [(WIDTH-1):0] mem [0:(DEPTH-1)];
reg [(PTR_W-1):0] wr_ptr, rd_ptr;
reg [PTR_W:0]     count;

wire enq_fire = enq_valid && enq_ready;
wire deq_fire = deq_valid && deq_ready;

assign enq_ready = count != DEPTH;
assign deq_valid = count != 0;
assign deq_data  = mem[rd_ptr];

always @(posedge clk) begin
  if (rst) begin
    wr_ptr <= 0;
    rd_ptr <= 0;
    count  <= 0;
  end else begin
    if (enq_fire) begin
      mem[wr_ptr] <= enq_data;
      wr_ptr <= wr_ptr + 1'b1;
    end
    if (deq_fire) begin
      rd_ptr <= rd_ptr + 1'b1;
    end
    case ({enq_fire, deq_fire})
      2'b10: count <= count + 1'b1;
      2'b01: count <= count - 1'b1;
      default: count <= count;
    endcase
  end
end

endmodule
