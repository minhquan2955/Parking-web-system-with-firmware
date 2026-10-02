#include "../include/access_controller.hpp"
#include <cassert>
#include <iostream>
struct Driver : parking::HardwareDriver {
 int opens=0,ticks=0;
 void open(parking::Gate,uint32_t) override {++opens;}
 void tick(uint32_t) override {++ticks;}
 void rest() override {}
};
int main() {
 using namespace parking;
 Driver driver; AccessController controller(driver);
 assert(!controller.begin(Gate::In,"1",0)); controller.setRegistered(true);
 assert(controller.begin(Gate::In,"1",0)); assert(!controller.begin(Gate::Out,"2",1));
 assert(!controller.accept({"other",Gate::In,true,true,3000},200));
 assert(!controller.accept({"1",Gate::Out,true,true,3000},200));
 assert(controller.busy());
 assert(controller.accept({"1",Gate::In,true,true,3000},500));
 assert(!controller.accept({"1",Gate::In,true,true,3000},501)); assert(driver.opens==1);
 assert(controller.begin(Gate::In,"3",3500));
 assert(controller.retryAllowed(2,6499)); assert(!controller.retryAllowed(3,4000));
 assert(!controller.accept({"3",Gate::In,true,true,3000},6500));
 assert(controller.tick(6500)); assert(driver.opens==1);
 assert(controller.begin(Gate::Out,"4",7000)); controller.boot();
 assert(!controller.accept({"4",Gate::Out,true,true,3000},7100)); assert(driver.opens==1);
 controller.setRegistered(true);
 assert(controller.begin(Gate::In,"5",0xffffff00u));
 assert(controller.accept({"5",Gate::In,true,true,3000},100)); assert(driver.opens==2);
 assert(controller.begin(Gate::In,"6",200));
 assert(!controller.accept({"6",Gate::In,false,false,0},201)); assert(!controller.busy());
 assert(controller.begin(Gate::In,"7",300));
 assert(!controller.accept({"7",Gate::In,true,true,10000},301)); assert(driver.opens==2);
 assert(controller.begin(Gate::In,"8",400)); controller.cancel();
 assert(!controller.accept({"8",Gate::In,true,true,3000},401));
 controller.setRegistered(false); assert(!controller.begin(Gate::In,"9",500));

 Presentations cards;
 assert(cards.observe(Gate::In,"00010203",0));
 assert(!cards.observe(Gate::In,"00010203",5000)); // Holding past cooldown is not removal.
 cards.absent(Gate::In,5100);
 assert(!cards.observe(Gate::In,"00010203",5500)); // Short dropout.
 assert(!cards.observe(Gate::In,"00010203",7500)); // Must clear absence even when rejected.
 cards.absent(Gate::In,7600);
 cards.uncertain(Gate::In); // Collision/read error is not proof of removal.
 assert(!cards.observe(Gate::In,"00010203",8800));
 cards.absent(Gate::In,9000); cards.absent(Gate::In,9500);
 assert(cards.observe(Gate::In,"00010203",10000));
 cards.absent(Gate::In,10001);
 assert(!cards.observe(Gate::In,"00010203",11002)); // Removal met, cooldown not met.
 assert(cards.observe(Gate::Out,"00010203",11003)); // Independent readers.
 assert(cards.observe(Gate::In,"00010204",11004)); // Different UID.
 // A presentation consumed while offline/BUSY stays consumed after reconnect.
 assert(!cards.observe(Gate::In,"00010204",20000));
 Presentations wrap;
 assert(wrap.observe(Gate::In,"A",0xfffff000u));
 wrap.absent(Gate::In,0xffffff00u);
 assert(wrap.observe(Gate::In,"A",1000));
 std::cout << "Firmware policy: deadline, retries, correlation, open-once, boot, denial, presence and wrap passed\n";
}
