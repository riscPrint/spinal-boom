module tb;
reg clock=0, reset=0, c=0;
reg [7:0] x=0;
reg [1:0] sel=0;
wire [7:0] a,b,d,s;
Scope dut(.*);
integer i;
initial begin
  $display("cycle,c,sel,x,a_REG,REG,d_REG,s_r,s_r_1,a,b,d,s");
  for(i=0;i<8;i=i+1) begin
    c=(i==0 || i==4 || i==7); sel=(i==4 || i==7)?1:0;
    x=(i==3)?23:10+i;
    #5; clock=1; #1;
    $display("%0d,%0d,%0d,%0d,%0d,%0d,%0d,%0d,%0d,%0d,%0d,%0d,%0d",i,c,sel,x,dut.a_REG,dut.REG,dut.d_REG,dut.s_r,dut.s_r_1,a,b,d,s);
    if(dut.a_REG !== x || dut.REG !== x || dut.d_REG !== x || dut.s_r !== x) $fatal(1,"conditional load");
    if(i>0 && dut.s_r_1 !== ((i==4)?23:9+i)) $fatal(1,"shift load");
    #4; clock=0;
  end
  $finish;
end
endmodule
